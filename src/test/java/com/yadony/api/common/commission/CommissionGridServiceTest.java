package com.yadony.api.common.commission;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.payments.currency.SupportedCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CommissionGridService — grilles datées, validation, avertissement de hausse, cache, audit")
class CommissionGridServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 18, 12, 0);
    private static final UUID ADMIN = UUID.randomUUID();

    /** Horloge réglable : le test avance le temps sans recréer le service ni vider le cache. */
    private static final class MutableClock extends Clock {
        private Instant now = T0.toInstant(ZoneOffset.UTC);

        void set(LocalDateTime t) { now = t.toInstant(ZoneOffset.UTC); }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private CommissionGridRepository repository;
    private AuditService auditService;
    private PlatformSettingsService settings;
    private MutableClock clock;
    private CommissionGridWriteLock writeLock;
    private CommissionGridService service;
    private final List<CommissionGridEntity> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repository = mock(CommissionGridRepository.class);
        auditService = mock(AuditService.class);
        settings = mock(PlatformSettingsService.class);
        clock = new MutableClock();
        when(settings.commissionRate()).thenReturn(new BigDecimal("0.05"));
        when(repository.findAllByOrderByEffectiveFromDesc()).thenAnswer(i -> new ArrayList<>(stored));
        when(repository.findByEffectiveFromAfter(any())).thenAnswer(i -> {
            LocalDateTime instant = i.getArgument(0);
            return stored.stream().filter(e -> e.getEffectiveFrom().isAfter(instant)).toList();
        });
        when(repository.save(any(CommissionGridEntity.class))).thenAnswer(i -> {
            CommissionGridEntity e = i.getArgument(0);
            ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
            stored.add(e);
            return e;
        });
        writeLock = mock(CommissionGridWriteLock.class);
        service = new CommissionGridService(repository, auditService, settings,
                new ConcurrentMapCacheManager("commission-grids"), writeLock, clock);
    }

    // ── Fabriques ────────────────────────────────────────────────────────────────

    private static CommissionTier tier(String from, String rate) {
        return new CommissionTier(new BigDecimal(from), new BigDecimal(rate));
    }

    private static List<CommissionTier> tiers776() {
        return List.of(tier("0", "0.07"), tier("100", "0.06"), tier("300", "0.05"));
    }

    private static Map<SupportedCurrency, BigDecimal> minimums() {
        return CommissionGridService.defaultMinimums();
    }

    private static CommissionGridDraft draft(List<CommissionTier> tiers, Map<SupportedCurrency, BigDecimal> minimums,
                                             LocalDateTime effectiveFrom) {
        return new CommissionGridDraft(tiers, minimums, effectiveFrom, null);
    }

    private CommissionGridEntity given(LocalDateTime effectiveFrom, List<CommissionTier> tiers,
                                       Map<SupportedCurrency, BigDecimal> minimums) {
        CommissionGridEntity e = new CommissionGridEntity(effectiveFrom, tiers, minimums, null, ADMIN);
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        stored.add(e);
        stored.sort((a, b) -> b.getEffectiveFrom().compareTo(a.getEffectiveFrom()));
        return e;
    }

    private static Map<SupportedCurrency, BigDecimal> minimumsWith(SupportedCurrency c, String value) {
        Map<SupportedCurrency, BigDecimal> m = new EnumMap<>(minimums());
        m.put(c, new BigDecimal(value));
        return m;
    }

    private static YadonyBusinessException thrown(Runnable call) {
        try {
            call.run();
        } catch (YadonyBusinessException e) {
            return e;
        }
        throw new AssertionError("YadonyBusinessException attendue");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> violations(YadonyBusinessException e) {
        return (Map<String, String>) e.getProperties().get("violations");
    }

    // ── Programmation ────────────────────────────────────────────────────────────

    @Test
    void schedule_validDraft_persistsAndAudits() {
        CommissionGridDraft draft = new CommissionGridDraft(tiers776(), minimums(), T0.plusDays(31), "Grille d'automne");

        CommissionGrid grid = service.schedule(draft, Set.of(), ADMIN);

        ArgumentCaptor<CommissionGridEntity> saved = ArgumentCaptor.forClass(CommissionGridEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCreatedBy()).isEqualTo(ADMIN);
        assertThat(saved.getValue().getNote()).isEqualTo("Grille d'automne");
        assertThat(grid.id()).isEqualTo(saved.getValue().getId());
        assertThat(grid.effectiveFrom()).isEqualTo(T0.plusDays(31));
        assertThat(grid.tiers()).hasSize(3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("COMMISSION_GRID"), eq(grid.id()), eq("COMMISSION_GRID_SCHEDULED"),
                eq(ADMIN), payload.capture());
        assertThat(payload.getValue()).containsKeys("tiers", "minimums", "effectiveFrom", "acknowledgedWarnings");
        assertThat(payload.getValue().get("tiers")).isEqualTo(List.of(
                Map.of("fromEur", "0", "rate", "0.07"),
                Map.of("fromEur", "100", "rate", "0.06"),
                Map.of("fromEur", "300", "rate", "0.05")));
        assertThat(payload.getValue().get("minimums")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) payload.getValue().get("minimums")).get("XOF")).isEqualTo("650");
        assertThat(payload.getValue().get("effectiveFrom")).isEqualTo("2026-11-18T12:00");
        assertThat(payload.getValue().get("acknowledgedWarnings")).isEqualTo(List.of());
    }

    static Stream<Arguments> invalidDrafts() {
        Map<SupportedCurrency, BigDecimal> withoutXof = new EnumMap<>(SupportedCurrency.class);
        withoutXof.putAll(CommissionGridService.defaultMinimums());
        withoutXof.remove(SupportedCurrency.XOF);
        LocalDateTime later = T0.plusDays(31);
        List<CommissionTier> eleven = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            eleven.add(tier(String.valueOf(i * 100), "0.05"));
        }
        return Stream.of(
                Arguments.of("première borne non nulle",
                        draft(List.of(tier("10", "0.07")), minimums(), later), "tiers[0].fromEur"),
                Arguments.of("bornes non croissantes",
                        draft(List.of(tier("0", "0.07"), tier("100", "0.06"), tier("100", "0.05")), minimums(), later),
                        "tiers[2].fromEur"),
                Arguments.of("bornes décroissantes",
                        draft(List.of(tier("0", "0.07"), tier("100", "0.06"), tier("50", "0.05")), minimums(), later),
                        "tiers[2].fromEur"),
                Arguments.of("aucune tranche", draft(List.of(), minimums(), later), "tiers"),
                Arguments.of("onze tranches", draft(eleven, minimums(), later), "tiers"),
                Arguments.of("taux de 0,31", draft(List.of(tier("0", "0.31")), minimums(), later), "tiers[0].rate"),
                Arguments.of("taux négatif", draft(List.of(tier("0", "-0.01")), minimums(), later), "tiers[0].rate"),
                Arguments.of("minimum manquant", draft(tiers776(), withoutXof, later), "minimums.XOF"),
                Arguments.of("minimum négatif",
                        draft(tiers776(), minimumsWith(SupportedCurrency.EUR, "-1"), later), "minimums.EUR"),
                Arguments.of("minimum EUR au-dessus de 5 €",
                        draft(tiers776(), minimumsWith(SupportedCurrency.EUR, "5.01"), later), "minimums.EUR"),
                Arguments.of("minimum XOF au-dessus de 5 € (3 279,785)",
                        draft(tiers776(), minimumsWith(SupportedCurrency.XOF, "3280"), later), "minimums.XOF"),
                Arguments.of("note trop longue", new CommissionGridDraft(tiers776(), minimums(), later, "x".repeat(501)),
                        "note"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDrafts")
    void schedule_rejects(String label, CommissionGridDraft draft, String field) {
        YadonyBusinessException e = thrown(() -> service.schedule(draft, Set.of(), ADMIN));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("validation");
        assertThat(violations(e)).containsKey(field);
        verify(repository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void schedule_minimumAtCap_isAccepted() {
        // Le plafond est inclusif : 5 € = 3 279,785 XOF.
        Map<SupportedCurrency, BigDecimal> atCap = new EnumMap<>(minimums());
        atCap.put(SupportedCurrency.EUR, new BigDecimal("5"));
        atCap.put(SupportedCurrency.XOF, new BigDecimal("3279.785"));

        assertThat(service.schedule(draft(tiers776(), atCap, T0.plusDays(31)), Set.of(), ADMIN)).isNotNull();
    }

    @Test
    void schedule_effectiveFromTooOld_rejectedWithDedicatedCode() {
        YadonyBusinessException e = thrown(() ->
                service.schedule(draft(tiers776(), minimums(), T0.minusMinutes(6)), Set.of(), ADMIN));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("commission-grid-effective-in-past");
        assertThat(violations(e)).containsKey("effectiveFrom");
    }

    @Test
    void schedule_effectiveFromNullOrWithinFiveMinutes_takesEffectNow() {
        service.schedule(draft(tiers776(), minimums(), null), Set.of(CommissionGridWarning.INCREASE_WITHIN_30_DAYS), ADMIN);
        service.schedule(draft(tiers776(), minimums(), T0.minusMinutes(5)),
                Set.of(CommissionGridWarning.INCREASE_WITHIN_30_DAYS), ADMIN);

        assertThat(stored).extracting(CommissionGridEntity::getEffectiveFrom).containsOnly(T0);
    }

    @Test
    void schedule_futureEffectiveFrom_keptAsIs() {
        CommissionGrid grid = service.schedule(draft(tiers776(), minimums(), T0.plusDays(45)), Set.of(), ADMIN);

        assertThat(grid.effectiveFrom()).isEqualTo(T0.plusDays(45));
    }

    @Test
    void schedule_increaseWithin30Days_requiresAcknowledgement() {
        CommissionGridDraft draft = draft(tiers776(), minimums(), T0.plusDays(10));

        YadonyBusinessException e = thrown(() -> service.schedule(draft, Set.of(), ADMIN));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo("commission-grid-warning-not-acknowledged");
        assertThat(e.getProperties().get("warnings")).isEqualTo(List.of("INCREASE_WITHIN_30_DAYS"));
        verify(repository, never()).save(any());

        CommissionGrid grid = service.schedule(draft, Set.of(CommissionGridWarning.INCREASE_WITHIN_30_DAYS), ADMIN);

        assertThat(grid.id()).isNotNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("COMMISSION_GRID"), eq(grid.id()), eq("COMMISSION_GRID_SCHEDULED"),
                eq(ADMIN), payload.capture());
        assertThat(payload.getValue().get("acknowledgedWarnings")).isEqualTo(List.of("INCREASE_WITHIN_30_DAYS"));
    }

    @Test
    void schedule_replacesPreviouslyScheduled() {
        CommissionGridEntity previous = given(T0.plusDays(40), tiers776(), minimums());

        CommissionGrid grid = service.schedule(draft(tiers776(), minimums(), T0.plusDays(50)), Set.of(), ADMIN);

        assertThat(previous.getDeletedAt()).isEqualTo(T0);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("COMMISSION_GRID"), eq(previous.getId()), eq("COMMISSION_GRID_CANCELLED"),
                eq(ADMIN), payload.capture());
        assertThat(payload.getValue()).containsEntry("replacedBy", grid.id());
    }

    @Test
    void schedule_neverTouchesAnEffectiveGrid() {
        CommissionGridEntity inForce = given(T0.minusDays(3), tiers776(), minimums());

        service.schedule(draft(tiers776(), minimums(), T0.plusDays(50)), Set.of(), ADMIN);

        assertThat(inForce.getDeletedAt()).isNull();
        assertThat(inForce.getEffectiveFrom()).isEqualTo(T0.minusDays(3));
    }

    // ── Avertissement de hausse ──────────────────────────────────────────────────

    @Test
    void warningsFor_decreaseOnly_none() {
        given(T0.minusDays(3), tiers776(), minimums());
        CommissionGridDraft lower = draft(
                List.of(tier("0", "0.06"), tier("100", "0.05"), tier("300", "0.04")), minimums(), T0.plusDays(5));

        assertThat(service.warningsFor(lower)).isEmpty();
    }

    @Test
    void warningsFor_identicalGrid_none() {
        given(T0.minusDays(3), tiers776(), minimums());

        assertThat(service.warningsFor(draft(tiers776(), minimums(), T0.plusDays(5)))).isEmpty();
    }

    @Test
    void warningsFor_minimumRaise_isIncrease() {
        given(T0.minusDays(3), tiers776(), minimums());
        CommissionGridDraft raisedMinimum = draft(tiers776(), minimumsWith(SupportedCurrency.CHF, "1.00"), T0.plusDays(5));

        assertThat(service.warningsFor(raisedMinimum)).containsExactly(CommissionGridWarning.INCREASE_WITHIN_30_DAYS);
    }

    @Test
    void warningsFor_rateRaiseOnOneTierOnly_isIncrease() {
        given(T0.minusDays(3), tiers776(), minimums());
        CommissionGridDraft higherTop = draft(
                List.of(tier("0", "0.07"), tier("100", "0.06"), tier("300", "0.051")), minimums(), T0.plusDays(5));

        assertThat(service.warningsFor(higherTop)).containsExactly(CommissionGridWarning.INCREASE_WITHIN_30_DAYS);
    }

    @Test
    void warningsFor_increaseTakingEffectAfter30Days_none() {
        given(T0.minusDays(3), tiers776(), minimums());
        CommissionGridDraft higher = draft(List.of(tier("0", "0.10")), minimums(), T0.plusDays(30));

        assertThat(service.warningsFor(higher)).isEmpty();
        assertThat(service.warningsFor(draft(List.of(tier("0", "0.10")), minimums(), T0.plusDays(30).minusSeconds(1))))
                .containsExactly(CommissionGridWarning.INCREASE_WITHIN_30_DAYS);
    }

    @Test
    void warningsFor_noGrid_compareWithFlatPlatformRate() {
        // Sans grille en vigueur : référence = taux plat de la plateforme (5 %), minimum 0.
        assertThat(service.warningsFor(draft(tiers776(), minimums(), T0.plusDays(5))))
                .containsExactly(CommissionGridWarning.INCREASE_WITHIN_30_DAYS);

        Map<SupportedCurrency, BigDecimal> zero = new EnumMap<>(SupportedCurrency.class);
        for (SupportedCurrency c : SupportedCurrency.values()) {
            zero.put(c, BigDecimal.ZERO);
        }
        assertThat(service.warningsFor(draft(List.of(tier("0", "0.04")), zero, T0.plusDays(5)))).isEmpty();
        assertThat(service.warningsFor(draft(List.of(tier("0", "0.05")), zero, T0.plusDays(5)))).isEmpty();
        assertThat(service.warningsFor(draft(List.of(tier("0", "0.0501")), zero, T0.plusDays(5))))
                .containsExactly(CommissionGridWarning.INCREASE_WITHIN_30_DAYS);
    }

    @Test
    void warningsFor_referenceIgnoresTheScheduledGridItReplaces() {
        given(T0.minusDays(3), tiers776(), minimums());
        given(T0.plusDays(20), List.of(tier("0", "0.10")), minimums());
        // Moins cher que la grille programmée, mais pas plus cher que celle en vigueur : la programmée
        // disparaît au remplacement, la référence est donc celle en vigueur.
        CommissionGridDraft draft = draft(tiers776(), minimums(), T0.plusDays(25));

        assertThat(service.warningsFor(draft)).isEmpty();
    }

    @Test
    void warningsFor_referenceIsTheGridInForceJustBeforeEffectiveFrom() {
        given(T0.minusDays(3), List.of(tier("0", "0.20")), minimums());
        // Une grille en vigueur à 20 % : tout brouillon à 7/6/5 est une baisse.
        assertThat(service.warningsFor(draft(tiers776(), minimums(), T0.plusDays(5)))).isEmpty();
    }

    @Test
    void warningsFor_invalidDraft_isRejected() {
        assertThat(thrown(() -> service.warningsFor(draft(List.of(tier("5", "0.07")), minimums(), T0.plusDays(5))))
                .getErrorCode()).isEqualTo("validation");
    }

    // ── Annulation ───────────────────────────────────────────────────────────────

    @Test
    void cancel_scheduledGrid_softDeletesAndAudits() {
        CommissionGridEntity scheduled = given(T0.plusDays(10), tiers776(), minimums());
        when(repository.findById(scheduled.getId())).thenReturn(Optional.of(scheduled));

        service.cancel(scheduled.getId(), ADMIN);

        assertThat(scheduled.getDeletedAt()).isEqualTo(T0);
        verify(auditService).log(eq("COMMISSION_GRID"), eq(scheduled.getId()), eq("COMMISSION_GRID_CANCELLED"),
                eq(ADMIN), anyMap());
    }

    @Test
    void cancel_effectiveGrid_409() {
        CommissionGridEntity inForce = given(T0.minusMinutes(1), tiers776(), minimums());
        when(repository.findById(inForce.getId())).thenReturn(Optional.of(inForce));

        YadonyBusinessException e = thrown(() -> service.cancel(inForce.getId(), ADMIN));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("commission-grid-already-effective");
        assertThat(inForce.getDeletedAt()).isNull();
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void cancel_gridEffectiveExactlyNow_409() {
        CommissionGridEntity inForce = given(T0, tiers776(), minimums());
        when(repository.findById(inForce.getId())).thenReturn(Optional.of(inForce));

        assertThat(thrown(() -> service.cancel(inForce.getId(), ADMIN)).getErrorCode())
                .isEqualTo("commission-grid-already-effective");
    }

    @Test
    @DisplayName("la date d'effet passe entre le contrôle et l'écriture : refus du trigger traduit en 409")
    void cancel_triggerRefusesAtFlush_409() {
        CommissionGridEntity scheduled = given(T0.plusSeconds(1), tiers776(), minimums());
        when(repository.findById(scheduled.getId())).thenReturn(Optional.of(scheduled));
        // Forme réelle : PSQLException « ERROR: commission_grid_immutable » enveloppée par Hibernate puis Spring.
        doThrow(new JpaSystemException(new RuntimeException("could not execute statement",
                new java.sql.SQLException("ERROR: commission_grid_immutable\n  Détail : operation UPDATE interdite"))))
                .when(repository).flush();

        YadonyBusinessException e = thrown(() -> service.cancel(scheduled.getId(), ADMIN));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("commission-grid-already-effective");
    }

    @Test
    @DisplayName("un autre échec d'écriture à l'annulation n'est pas maquillé en 409")
    void cancel_otherFlushFailure_isRethrown() {
        CommissionGridEntity scheduled = given(T0.plusDays(1), tiers776(), minimums());
        when(repository.findById(scheduled.getId())).thenReturn(Optional.of(scheduled));
        JpaSystemException failure = new JpaSystemException(new RuntimeException("connexion perdue"));
        doThrow(failure).when(repository).flush();

        assertThatThrownBy(() -> service.cancel(scheduled.getId(), ADMIN)).isSameAs(failure);
    }

    @Test
    @DisplayName("programmer et annuler prennent le verrou des grilles avant toute lecture")
    void scheduleAndCancel_takeTheWriteLockFirst() {
        CommissionGridEntity scheduled = given(T0.plusDays(40), tiers776(), minimums());
        when(repository.findById(scheduled.getId())).thenReturn(Optional.of(scheduled));
        InOrder order = inOrder(writeLock, repository);

        service.schedule(draft(tiers776(), minimums(), T0.plusDays(50)), Set.of(), ADMIN);
        order.verify(writeLock).acquire();
        order.verify(repository).findAllByOrderByEffectiveFromDesc();

        service.cancel(scheduled.getId(), ADMIN);
        order.verify(writeLock).acquire();
        order.verify(repository).findById(scheduled.getId());
    }

    @Test
    void cancel_unknownId_404() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        YadonyBusinessException e = thrown(() -> service.cancel(id, ADMIN));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getErrorCode()).isEqualTo("commission-grid-not-found");
    }

    // ── Lecture et cache ─────────────────────────────────────────────────────────

    @Test
    void current_withoutGrid_isEmpty() {
        assertThat(service.current()).isEmpty();
        assertThat(service.scheduled()).isEmpty();
    }

    @Test
    void currentScheduledAndActiveAt_pickByEffectiveFrom() {
        CommissionGridEntity old = given(T0.minusDays(30), tiers776(), minimums());
        CommissionGridEntity recent = given(T0.minusDays(2), List.of(tier("0", "0.04")), minimums());
        CommissionGridEntity next = given(T0.plusDays(9), List.of(tier("0", "0.03")), minimums());
        CommissionGridEntity later = given(T0.plusDays(20), List.of(tier("0", "0.02")), minimums());

        assertThat(service.current()).map(CommissionGrid::id).contains(recent.getId());
        assertThat(service.scheduled()).map(CommissionGrid::id).contains(next.getId());
        assertThat(service.activeAt(T0.minusDays(10))).map(CommissionGrid::id).contains(old.getId());
        assertThat(service.activeAt(T0.minusDays(2).minusNanos(1))).map(CommissionGrid::id).contains(old.getId());
        assertThat(service.activeAt(T0.minusDays(2))).map(CommissionGrid::id).contains(recent.getId());
        assertThat(service.activeAt(T0.plusDays(25))).map(CommissionGrid::id).contains(later.getId());
        assertThat(service.activeAt(T0.minusDays(31))).isEmpty();
    }

    @Test
    void activeAt_switchesAtEffectiveFrom_withWarmCache() {
        CommissionGridEntity a = given(T0.minusDays(1), tiers776(), minimums());
        CommissionGridEntity b = given(T0.plusMinutes(1), List.of(tier("0", "0.04")), minimums());
        assertThat(service.current()).map(CommissionGrid::id).contains(a.getId());

        clock.set(T0.plusMinutes(1));

        assertThat(service.current()).map(CommissionGrid::id).contains(b.getId());
        assertThat(service.scheduled()).isEmpty();
        verify(repository, times(1)).findAllByOrderByEffectiveFromDesc();
    }

    @Test
    void schedule_evictsCache() {
        assertThat(service.scheduled()).isEmpty();

        service.schedule(draft(tiers776(), minimums(), T0.plusDays(40)), Set.of(), ADMIN);

        assertThat(service.scheduled()).isPresent();
    }

    @Test
    void cancel_evictsCache() {
        CommissionGridEntity scheduled = given(T0.plusDays(10), tiers776(), minimums());
        when(repository.findById(scheduled.getId())).thenReturn(Optional.of(scheduled));
        assertThat(service.scheduled()).isPresent();

        service.cancel(scheduled.getId(), ADMIN);
        stored.remove(scheduled);

        assertThat(service.scheduled()).isEmpty();
    }

    @Test
    void require_knownGrid_returnsIt_unknown404() {
        CommissionGridEntity grid = given(T0.minusDays(1), tiers776(), minimums());
        UUID unknown = UUID.randomUUID();
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        assertThat(service.require(grid.getId()).id()).isEqualTo(grid.getId());
        YadonyBusinessException e = thrown(() -> service.require(unknown));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getErrorCode()).isEqualTo("commission-grid-not-found");
    }

    @Test
    void require_gridMissingFromWarmCache_isReadFromRepository() {
        service.current();
        CommissionGridEntity fresh = given(T0.minusDays(1), tiers776(), minimums());
        when(repository.findById(fresh.getId())).thenReturn(Optional.of(fresh));

        assertThat(service.require(fresh.getId()).id()).isEqualTo(fresh.getId());
    }

    @Test
    void history_includesCancelledGrids() {
        CommissionGridEntity cancelled = new CommissionGridEntity(T0.plusDays(5), tiers776(), minimums(), "Annulée", ADMIN);
        ReflectionTestUtils.setField(cancelled, "id", UUID.randomUUID());
        cancelled.setDeletedAt(T0.minusHours(1));
        ReflectionTestUtils.setField(cancelled, "createdAt", T0.minusDays(1));
        CommissionGridEntity kept = new CommissionGridEntity(T0.minusDays(5), tiers776(), minimums(), null, ADMIN);
        ReflectionTestUtils.setField(kept, "id", UUID.randomUUID());
        Pageable page = PageRequest.of(0, 20);
        when(repository.findHistory(page)).thenReturn(new PageImpl<>(List.of(cancelled, kept), page, 2));

        List<CommissionGridHistoryEntry> entries = service.history(page).getContent();

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).grid().id()).isEqualTo(cancelled.getId());
        assertThat(entries.get(0).cancelledAt()).isEqualTo(T0.minusHours(1));
        assertThat(entries.get(0).note()).isEqualTo("Annulée");
        assertThat(entries.get(0).createdBy()).isEqualTo(ADMIN);
        assertThat(entries.get(0).createdAt()).isEqualTo(T0.minusDays(1));
        assertThat(entries.get(1).cancelledAt()).isNull();
    }

    @Test
    void defaultMinimums_values() {
        Map<SupportedCurrency, BigDecimal> defaults = CommissionGridService.defaultMinimums();

        assertThat(defaults).containsOnlyKeys(SupportedCurrency.values());
        assertThat(defaults.get(SupportedCurrency.EUR)).isEqualByComparingTo("1.00");
        assertThat(defaults.get(SupportedCurrency.XOF)).isEqualByComparingTo("650");
        assertThat(defaults.get(SupportedCurrency.XAF)).isEqualByComparingTo("650");
        assertThat(defaults.get(SupportedCurrency.USD)).isEqualByComparingTo("1.10");
        assertThat(defaults.get(SupportedCurrency.CAD)).isEqualByComparingTo("1.50");
        assertThat(defaults.get(SupportedCurrency.GBP)).isEqualByComparingTo("0.90");
        assertThat(defaults.get(SupportedCurrency.CHF)).isEqualByComparingTo("0.95");
    }

    @Test
    void publicConstructor_isTheOnlyAutowiredOne() {
        long autowired = java.util.Arrays.stream(CommissionGridService.class.getDeclaredConstructors())
                .filter(c -> c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class))
                .peek(c -> assertThat(java.lang.reflect.Modifier.isPublic(c.getModifiers())).isTrue())
                .count();
        assertThat(autowired).isEqualTo(1);
    }
}
