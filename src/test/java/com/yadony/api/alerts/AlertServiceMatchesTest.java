package com.yadony.api.alerts;

import com.yadony.api.alerts.AlertDirection;
import com.yadony.api.alerts.dto.CorridorAlertRequest;
import com.yadony.api.alerts.dto.CorridorAlertResponse;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.dto.MatchingRequestDto;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.entity.PackageRequestStatus;
import com.yadony.api.requests.repository.PackageRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceMatchesTest {

    @Mock CorridorAlertRepository alertRepository;
    @Mock UserRepository userRepository;
    @Mock PackageRequestRepository packageRequestRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock com.yadony.api.common.BlockVisibility blockVisibility;
    @InjectMocks AlertService service;

    final String uid = "firebase-uid";
    final UUID ownerId = UUID.randomUUID();
    final UUID alertId = UUID.randomUUID();
    UserEntity owner;

    @BeforeEach
    void setup() {
        owner = new UserEntity();
        setId(owner, ownerId);
        lenient().when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(owner));
        // Les fixtures vivent en juillet 2026 : l'horloge réelle les rendrait expirées.
        service.useClock(java.time.Clock.fixed(
                java.time.Instant.parse("2026-07-15T12:00:00Z"), java.time.ZoneOffset.UTC));
    }

    private static void setId(Object target, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private CorridorAlertEntity alert(boolean active) {
        CorridorAlertEntity a = new CorridorAlertEntity();
        setId(a, alertId);
        a.setOwnerId(ownerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDateFrom(LocalDate.of(2026, 7, 1));
        a.setDateTo(LocalDate.of(2026, 7, 31));
        a.setMinWeightKg(new BigDecimal("2.00"));
        a.setContentCategories(List.of("Documents"));
        a.setActive(active);
        return a;
    }

    private PackageRequestEntity pkg(String content, BigDecimal weight, LocalDate date) {
        PackageRequestEntity p = new PackageRequestEntity();
        setId(p, UUID.randomUUID());
        p.setSenderId(UUID.randomUUID());
        p.setDepartureCity("Paris");
        p.setArrivalCity("Bamako");
        p.setContentCategory(content);
        p.setWeightKg(weight);
        p.setDesiredDate(date);
        p.setDateToleranceDays((short) 2);
        p.setStatus(PackageRequestStatus.OPEN);
        return p;
    }

    /** Creates a UserEntity with the given ID set via reflection. */
    private UserEntity senderWithId(UUID id) {
        UserEntity u = new UserEntity();
        setId(u, id);
        return u;
    }

    @Test
    void getMatches_filtersByWeightDateAndCategory() {
        PackageRequestEntity match = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        PackageRequestEntity tooLight = pkg("Documents", new BigDecimal("1.00"), LocalDate.of(2026, 7, 10));
        PackageRequestEntity outOfWindow = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 8, 15));
        PackageRequestEntity wrongCategory = pkg("Vêtements", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert(true)));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(match, tooLight, outOfWindow, wrongCategory));
        // Item 5: service now calls findAllById — return a sender for the one matching package
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(senderWithId(match.getSenderId())));

        List<MatchingRequestDto> matches = service.getMatches(uid, alertId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).contentType()).isEqualTo("Documents");
        assertThat(matches.get(0).weightKg()).isEqualTo(3.0);
    }

    /** Symétrique côté voyageur : le colis d'un expéditeur bloqué ne remonte pas. */
    @Test
    void getMatches_omitsBlockedSenders() {
        PackageRequestEntity fromBlocked = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        PackageRequestEntity fromVisible = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 11));

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert(true)));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(fromBlocked, fromVisible));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(java.util.Set.of(fromBlocked.getSenderId()));
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(senderWithId(fromVisible.getSenderId())));

        List<MatchingRequestDto> matches = service.getMatches(uid, alertId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).id()).isEqualTo(fromVisible.getId().toString());
    }

    // C1 (bug adjacent) : p.getContentCategory() est une liste jointe par virgule
    // (multi-sélection) — comparer la chaîne entière à chaque catégorie voulue ne
    // matchait jamais un colis multi-catégories. fitsAlertCategory doit désormais
    // splitter et comparer item par item (aligné sur BidContentRules).
    @Test
    void getMatches_multiCategoryPackage_matchesOnAnyItem() {
        PackageRequestEntity multiCategory = pkg(
                "Alimentation sèche, Vêtements & tissus", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        CorridorAlertEntity a = alert(true);
        a.setContentCategories(List.of("Vêtements & tissus"));

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(a));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(multiCategory));
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(senderWithId(multiCategory.getSenderId())));

        List<MatchingRequestDto> matches = service.getMatches(uid, alertId);

        assertThat(matches).hasSize(1);
    }

    @Test
    void getMatches_multiCategoryPackage_noOverlap_doesNotMatch() {
        PackageRequestEntity multiCategory = pkg(
                "Alimentation sèche, Cadeaux & jouets", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        CorridorAlertEntity a = alert(true);
        a.setContentCategories(List.of("Vêtements & tissus"));

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(a));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(multiCategory));

        List<MatchingRequestDto> matches = service.getMatches(uid, alertId);

        assertThat(matches).isEmpty();
    }

    @Test
    void getMatches_noFilters_returnsAllOpen() {
        PackageRequestEntity p = pkg("Anything", new BigDecimal("0.50"), LocalDate.of(2030, 1, 1));
        CorridorAlertEntity a = alert(true);
        a.setDateFrom(null);
        a.setDateTo(null);
        a.setMinWeightKg(null);
        a.setContentCategories(List.of());
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(a));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(p));
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(senderWithId(p.getSenderId())));

        assertThat(service.getMatches(uid, alertId)).hasSize(1);
    }

    @Test
    void getMatches_notOwner_throwsNotFound() {
        CorridorAlertEntity foreign = alert(true);
        foreign.setOwnerId(UUID.randomUUID());
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getMatches(uid, alertId))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void list_returnsItemsWithMatchCount() {
        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(alert(true)));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10))));

        List<CorridorAlertResponse> list = service.list(uid);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).matchCount()).isEqualTo(1L);
    }

    @Test
    void update_togglesActiveAndFilters() {
        CorridorAlertEntity existing = alert(true);
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(existing));
        when(alertRepository.save(existing)).thenReturn(existing);
        when(packageRequestRepository.findOpenByCorridor(any(), any())).thenReturn(List.of());

        CorridorAlertRequest req = new CorridorAlertRequest("Lyon", "FR", "Dakar", "SN",
                null, null, null, List.of(), AlertDirection.TRAVELER_WANTS_PACKAGES, null);
        CorridorAlertResponse resp = service.update(uid, alertId, req, false);

        assertThat(existing.getDepartureCity()).isEqualTo("Lyon");
        assertThat(existing.getArrivalCity()).isEqualTo("Dakar");
        assertThat(existing.isActive()).isFalse();
        assertThat(resp.active()).isFalse();
    }

    @Test
    void update_notOwner_throwsNotFound() {
        CorridorAlertEntity foreign = alert(true);
        foreign.setOwnerId(UUID.randomUUID());
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.update(uid, alertId,
                new CorridorAlertRequest("A", null, "B", null, null, null, null, List.of(), AlertDirection.TRAVELER_WANTS_PACKAGES, null), null))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void delete_softDeletesOwnedAlert() {
        CorridorAlertEntity existing = alert(true);
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(existing));
        when(alertRepository.save(existing)).thenReturn(existing);

        service.delete(uid, alertId);

        assertThat(existing.getDeletedAt()).isNotNull();
        verify(alertRepository).save(existing);
    }

    // ── Contenu propre, nouveautés, consultation ───────────────────────────

    private static void setCreatedAt(Object target, java.time.LocalDateTime at) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(target, at);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** Un utilisateur double rôle publie un colis sur le corridor de sa propre alerte :
     *  il ne doit pas se retrouver dans ses propres correspondances. */
    @Test
    void getMatches_omitsOwnPackages() {
        PackageRequestEntity mine = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        mine.setSenderId(ownerId);
        PackageRequestEntity other = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 11));

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert(true)));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(mine, other));
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(senderWithId(other.getSenderId())));

        List<MatchingRequestDto> matches = service.getMatches(uid, alertId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).id()).isEqualTo(other.getId().toString());
    }

    @Test
    void list_neverSeen_everyMatchIsNew() {
        PackageRequestEntity a = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        PackageRequestEntity b = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 11));
        setCreatedAt(a, java.time.LocalDateTime.of(2026, 6, 1, 10, 0));
        setCreatedAt(b, java.time.LocalDateTime.of(2026, 6, 20, 10, 0));

        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(alert(true)));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako")).thenReturn(List.of(a, b));

        List<CorridorAlertResponse> list = service.list(uid, null);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).matchCount()).isEqualTo(2);
        assertThat(list.get(0).newMatchCount()).isEqualTo(2);
    }

    @Test
    void list_seenOnce_onlyLaterMatchesAreNew() {
        PackageRequestEntity before = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        PackageRequestEntity after = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 11));
        PackageRequestEntity undated = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 12));
        setCreatedAt(before, java.time.LocalDateTime.of(2026, 6, 1, 10, 0));
        setCreatedAt(after, java.time.LocalDateTime.of(2026, 6, 20, 10, 0));
        CorridorAlertEntity seen = alert(true);
        seen.setLastSeenAt(java.time.LocalDateTime.of(2026, 6, 10, 9, 0));

        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(seen));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(before, after, undated));

        CorridorAlertResponse r = service.list(uid, null).get(0);

        // Trois correspondances, une seule postérieure à la consultation ; un colis
        // sans horodatage ne compte jamais comme nouveau.
        assertThat(r.matchCount()).isEqualTo(3);
        assertThat(r.newMatchCount()).isEqualTo(1);
    }

    @Test
    void markSeen_stampsNowAndResetsNewMatchCount() {
        PackageRequestEntity old = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        setCreatedAt(old, java.time.LocalDateTime.of(2026, 6, 1, 10, 0));
        CorridorAlertEntity entity = alert(true);

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(entity));
        when(alertRepository.save(entity)).thenReturn(entity);
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako")).thenReturn(List.of(old));

        CorridorAlertResponse r = service.markSeen(uid, alertId);

        assertThat(entity.getLastSeenAt()).isNotNull();
        assertThat(r.matchCount()).isEqualTo(1);
        assertThat(r.newMatchCount()).isZero();
    }

    @Test
    void markSeen_foreignAlert_notFound() {
        CorridorAlertEntity foreign = alert(true);
        foreign.setOwnerId(UUID.randomUUID());
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(foreign));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.markSeen(uid, alertId))
                .isInstanceOf(com.yadony.api.common.YadonyNotFoundException.class);
    }

    // ── Matching temps réel côté colis ─────────────────────────────────────

    @Test
    void findTravelerAlertsMatchingPackage_keepsOnlyMatchingLiveForeignAlerts() {
        PackageRequestEntity p = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));

        CorridorAlertEntity match = alert(true);
        CorridorAlertEntity mine = alert(true);
        setId(mine, UUID.randomUUID());
        mine.setOwnerId(p.getSenderId());
        CorridorAlertEntity expired = alert(true);
        setId(expired, UUID.randomUUID());
        expired.setDateFrom(LocalDate.of(2020, 1, 1));
        expired.setDateTo(LocalDate.of(2020, 1, 31));
        CorridorAlertEntity otherCorridor = alert(true);
        setId(otherCorridor, UUID.randomUUID());
        otherCorridor.setArrivalCity("Dakar");
        CorridorAlertEntity tooHeavy = alert(true);
        setId(tooHeavy, UUID.randomUUID());
        tooHeavy.setMinWeightKg(new BigDecimal("10.00"));

        when(alertRepository.findAllByActiveTrueAndDirection(AlertDirection.TRAVELER_WANTS_PACKAGES))
                .thenReturn(List.of(match, mine, expired, otherCorridor, tooHeavy));

        List<CorridorAlertEntity> hits = service.findTravelerAlertsMatchingPackage(p);

        assertThat(hits).containsExactly(match);
    }

    @Test
    void findTravelerAlertsMatchingPackage_ignoresNonOpenRequest() {
        PackageRequestEntity p = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        p.setStatus(PackageRequestStatus.CANCELLED);

        assertThat(service.findTravelerAlertsMatchingPackage(p)).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(alertRepository);
    }

    @Test
    void isExpired_onlyAfterDateTo() {
        CorridorAlertEntity a = alert(true); // dateTo = 2026-07-31
        assertThat(AlertService.isExpired(a, LocalDate.of(2026, 7, 31))).isFalse();
        assertThat(AlertService.isExpired(a, LocalDate.of(2026, 8, 1))).isTrue();
        a.setDateTo(null);
        assertThat(AlertService.isExpired(a, LocalDate.of(2030, 1, 1))).isFalse();
    }

    @Test
    void get_returnsOwnedAlertWithCounters() {
        PackageRequestEntity a = pkg("Documents", new BigDecimal("3.00"), LocalDate.of(2026, 7, 10));
        setCreatedAt(a, java.time.LocalDateTime.of(2026, 6, 1, 10, 0));
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(alert(true)));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako")).thenReturn(List.of(a));

        CorridorAlertResponse r = service.get(uid, alertId);

        assertThat(r.id()).isEqualTo(alertId);
        assertThat(r.matchCount()).isEqualTo(1);
        assertThat(r.newMatchCount()).isEqualTo(1);
    }
}
