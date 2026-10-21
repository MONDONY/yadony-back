package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserBlockEntity;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.matching.dto.SimilarAnnouncementResponse;
import com.yadony.api.matching.dto.UnmetCriterionDto;
import com.yadony.api.matching.ranking.TripRankingProperties;
import com.yadony.api.matching.ranking.TripScoreCalculator;
import com.yadony.api.payments.cash.PaymentMethod;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recherche de trajets en deux blocs (spec 2026-10-21-classement-annonces) : bloc 1 des
 * annonces exactes classées par score caché, bloc 2 des annonces du même trajet qui ratent
 * un critère souple, avec leurs écarts.
 *
 * <p>Ordre des appels {@code findAll} mockés : 1) annonces exactes (candidats ou page),
 * 2) vivier strict du bloc 2 quand il est calculé.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService.searchAnnouncements — classement en deux blocs")
class AnnouncementSearchRankingTest {

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private PriceGridService priceGridService;
    @Mock private com.yadony.api.country.FlagService flagService;
    @Mock private com.yadony.api.common.StorageService storageService;
    @Mock private FavoriteRepository favoriteRepository;
    @Mock private ActiveCurrencyResolver activeCurrencyResolver;
    @Mock private ExchangeRateService exchangeRateService;
    @Mock private com.yadony.api.requests.repository.PackageRequestRepository packageRequestRepository;
    @Mock private com.yadony.api.requests.repository.NegotiationThreadRepository negotiationThreadRepository;
    @Mock private com.yadony.api.notifications.NotificationDispatcher notificationDispatcher;

    private AnnouncementService service;
    private TripScoreCalculator scoreCalculator;
    private final Map<UUID, UserEntity> users = new HashMap<>();
    /** Annonces créées par le test : servies par {@code findAllById} (chargement de la page). */
    private final Map<UUID, AnnouncementEntity> announcements = new HashMap<>();
    /** Lectures de candidats ({@code findAll} ou {@code findRankingRows}), dans l'ordre des appels. */
    private final List<Read> reads = new ArrayList<>();

    private record Read(Specification<AnnouncementEntity> spec, Pageable pageable, boolean rows) {
    }
    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void init() {
        lenient().when(activeCurrencyResolver.resolveDisplay(any())).thenReturn("EUR");
        lenient().when(exchangeRateService.convert(any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(List.of());
        stubFindAllById();
        lenient().when(userRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<UUID> ids = inv.getArgument(0);
            return StreamSupport.stream(ids.spliterator(), false)
                    .map(users::get).filter(java.util.Objects::nonNull).toList();
        });
        YadonyConfigProperties config = new YadonyConfigProperties(null, null,
                new YadonyConfigProperties.Urgency(3), null);
        AnnouncementSearchMapper realMapper = new AnnouncementSearchMapper(
                userRepository, bidRepository, priceGridService, storageService,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3));
        TripRankingProperties props = new TripRankingProperties(null, 0, 0, 0, 0);
        scoreCalculator = org.mockito.Mockito.spy(new TripScoreCalculator(props));
        service = new AnnouncementService(
                announcementRepository, bidRepository, userRepository,
                auditService, eventPublisher, config,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3),
                priceGridService, flagService,
                storageService, favoriteRepository, activeCurrencyResolver, exchangeRateService, realMapper,
                packageRequestRepository, negotiationThreadRepository, notificationDispatcher,
                mock(com.yadony.api.common.BlockVisibility.class),
                mock(AnnouncementViewService.class),
                mock(TravelerPayoutNetworksPort.class),
                props, scoreCalculator);
    }

    // ─── Fabriques ────────────────────────────────────────────────────────────

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Voyageur sans historique (fiabilité 0,8, note a priori). */
    private UserEntity traveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.getRoles().add(Role.TRAVELER);
        setId(u, UUID.randomUUID());
        users.put(u.getId(), u);
        return u;
    }

    /** Voyageur très fiable : 100 envois, 50 avis à 5. */
    private UserEntity greatTraveler() {
        UserEntity u = traveler();
        u.setTotalDeliveries(100);
        u.setAverageRating(new BigDecimal("5.0"));
        u.setRatingCount(50);
        return u;
    }

    /** Voyageur peu fiable : annulations et absences, espèces seules sur ses trajets. */
    private UserEntity badTraveler() {
        UserEntity u = traveler();
        u.setCancellationCount(5);
        u.setNoShowCount(3);
        return u;
    }

    private AnnouncementEntity trip(UserEntity t, int daysAhead) {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, UUID.randomUUID());
        a.setTravelerId(t.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(TODAY.plusDays(daysAhead));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setPricePerKgEur(BigDecimal.valueOf(5));
        a.setCurrency("EUR");
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG");
        a.setPickupLat(BigDecimal.valueOf(49.009));
        a.setPickupLng(BigDecimal.valueOf(2.547));
        a.setDeliveryAddressLabel("DSS");
        a.setDeliveryLat(BigDecimal.valueOf(14.739));
        a.setDeliveryLng(BigDecimal.valueOf(-17.490));
        announcements.put(a.getId(), a);
        return a;
    }

    private List<AnnouncementEntity> trips(UserEntity t, int count, int daysAhead) {
        List<AnnouncementEntity> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.add(trip(t, daysAhead));
        }
        return out;
    }

    private void stubFindAllById() {
        lenient().when(announcementRepository.findAllById(any())).thenAnswer(inv -> {
            Iterable<UUID> ids = inv.getArgument(0);
            return StreamSupport.stream(ids.spliterator(), false)
                    .map(announcements::get).filter(java.util.Objects::nonNull).toList();
        });
    }

    /** Change l'id d'une annonce déjà créée en gardant le registre à jour. */
    private void reId(AnnouncementEntity a, UUID id) {
        announcements.remove(a.getId());
        setId(a, id);
        announcements.put(id, a);
    }

    @SafeVarargs
    private void stubFindAll(List<AnnouncementEntity>... pages) {
        stubReads(java.util.Arrays.stream(pages).map(p -> (Page<AnnouncementEntity>) new PageImpl<>(p)).toList());
    }

    /**
     * Sert les lectures de candidats dans l'ordre des appels, qu'elles passent par
     * {@code findAll} (page SQL des tris manuels et du compteur) ou par {@code findRankingRows}
     * (projection du score et du vivier du bloc 2). La dernière réponse se répète, comme
     * {@code thenReturn}.
     */
    private void stubReads(List<Page<AnnouncementEntity>> pages) {
        java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<Page<AnnouncementEntity>> nextPage =
                () -> pages.get(Math.min(next.getAndIncrement(), pages.size() - 1));
        lenient().when(announcementRepository.findAll(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    reads.add(new Read(inv.getArgument(0), inv.getArgument(1), false));
                    return nextPage.get();
                });
        lenient().when(announcementRepository.findRankingRows(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class)))
                .thenAnswer(inv -> {
                    reads.add(new Read(inv.getArgument(0), inv.getArgument(1), true));
                    return nextPage.get().getContent().stream().map(RankingRow::of).toList();
                });
    }

    /** Paramètres de recherche, null = absent. */
    private final class Q {
        String dep = "Paris", arr = "Dakar", sortBy, sortDir = "asc", viewer;
        LocalDate from, to;
        BigDecimal minKg, maxPrice, minRating;
        Boolean kyc;
        Set<PaymentMethod> payments = Set.of();
        boolean includeFull;
        Pageable pageable = PageRequest.of(0, 20);

        TripSearchPage<AnnouncementSearchResponse> run() {
            Page<AnnouncementSearchResponse> page = service.searchAnnouncements(
                    dep, arr, from, to, minKg, null, maxPrice, minRating, null, null,
                    null, kyc, null, null, null, null,
                    sortBy, sortDir, pageable, viewer, null,
                    new AnnouncementSearchExtras(null, payments, includeFull));
            assertThat(page).isInstanceOf(TripSearchPage.class);
            return (TripSearchPage<AnnouncementSearchResponse>) page;
        }
    }

    private static List<UUID> ids(Page<AnnouncementSearchResponse> page) {
        return page.getContent().stream().map(AnnouncementSearchResponse::id).toList();
    }

    private static List<UUID> similarIds(TripSearchPage<AnnouncementSearchResponse> page) {
        return page.similarAnnouncements().stream().map(s -> s.announcement().id()).toList();
    }

    /** Toutes les Specifications des lectures de candidats, dans l'ordre des appels. */
    private List<Specification<AnnouncementEntity>> capturedSpecs(int calls) {
        assertThat(reads).hasSize(calls);
        return reads.stream().map(Read::spec).toList();
    }

    /** Exécute une Specification sur des mocks Criteria pour savoir quels filtres elle porte. */
    private record Probe(Root<AnnouncementEntity> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        @SuppressWarnings("unchecked")
        static Probe of(Specification<AnnouncementEntity> spec) {
            Root<AnnouncementEntity> root = mock(Root.class, RETURNS_DEEP_STUBS);
            CriteriaQuery<?> query = mock(CriteriaQuery.class, RETURNS_DEEP_STUBS);
            CriteriaBuilder cb = mock(CriteriaBuilder.class, RETURNS_DEEP_STUBS);
            spec.toPredicate(root, query, cb);
            return new Probe(root, query, cb);
        }
    }

    // ─── Bloc 1 : ordre par score ─────────────────────────────────────────────

    @Test
    @DisplayName("tri par défaut : le score passe devant la date de départ")
    void exactSortedByScore_notByDate() {
        AnnouncementEntity soonBad = trip(badTraveler(), 2);
        soonBad.setAcceptedPaymentMethods(EnumSet.of(PaymentMethod.CASH));
        AnnouncementEntity laterGreat = trip(greatTraveler(), 20);
        // La base renvoie les candidats par date de départ : le bon voyageur arrive second.
        stubFindAll(List.of(soonBad, laterGreat));
        Q q = new Q();
        q.dep = null;
        q.arr = null;

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(ids(page)).containsExactly(laterGreat.getId(), soonBad.getId());
        assertThat(page.getTotalElements()).isEqualTo(2);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(announcementRepository).findRankingRows(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), pageable.capture());
        // Sans aucune ville : plafond maxCandidatesWithoutCity (300), pas maxCandidates.
        assertThat(pageable.getValue().getPageSize()).isEqualTo(300);
        assertThat(pageable.getValue().getSort().getOrderFor("departureDate")).isNotNull();
        assertThat(pageable.getValue().getSort().getOrderFor("travelerIsPro")).isNull();
    }

    @Test
    @DisplayName("score avec une ville : plafond maxCandidates (1000)")
    void scoreWithCity_usesMaxCandidates() {
        stubFindAll(trips(traveler(), 7, 11));
        Q q = new Q();

        q.run();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(announcementRepository).findRankingRows(ArgumentMatchers.<Specification<AnnouncementEntity>>any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1000);
    }

    @Test
    @DisplayName("score : découpe la page demandée dans le classement complet")
    void exactSortedByScore_slicesRequestedPage() {
        AnnouncementEntity a = trip(badTraveler(), 2);
        AnnouncementEntity b = trip(traveler(), 5);
        AnnouncementEntity c = trip(greatTraveler(), 9);
        stubFindAll(List.of(a, b, c));
        Q q = new Q();
        q.dep = null;
        q.arr = null;
        q.pageable = PageRequest.of(1, 2);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(ids(page)).containsExactly(a.getId());
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("un voyageur Pro ne devance plus un trajet mieux classé")
    void proTraveler_doesNotOutrankBetterScore() {
        AnnouncementEntity pro = trip(badTraveler(), 15);
        pro.setTravelerIsPro(true);
        AnnouncementEntity better = trip(greatTraveler(), 15);
        stubFindAll(List.of(pro, better));
        Q q = new Q();
        q.dep = null;
        q.arr = null;

        assertThat(ids(q.run())).containsExactly(better.getId(), pro.getId());
    }

    // ─── Bloc 2 : déclenchement ───────────────────────────────────────────────

    @Test
    @DisplayName("6 annonces exactes → bloc 2 avec l'écart de date")
    void sixExact_returnsSimilarBlock() {
        UserEntity t = traveler();
        List<AnnouncementEntity> exact = trips(t, 6, 11);
        AnnouncementEntity offDate = trip(t, 20);
        List<AnnouncementEntity> pool = new ArrayList<>(exact);
        pool.add(offDate);
        stubFindAll(exact, pool);
        Q q = new Q();
        q.from = TODAY.plusDays(10);
        q.to = TODAY.plusDays(12);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(page.getContent()).hasSize(6);
        assertThat(similarIds(page)).containsExactly(offDate.getId());
        assertThat(page.similarAnnouncements().get(0).unmet())
                .containsExactly(new UnmetCriterionDto("DATE", TODAY.plusDays(12).toString(),
                        TODAY.plusDays(20).toString()));
    }

    @Test
    @DisplayName("7 annonces exactes → pas de bloc 2, pas de requête du vivier")
    void sevenExact_noSimilarBlock() {
        stubFindAll(trips(traveler(), 7, 11));
        Q q = new Q();
        q.from = TODAY.plusDays(10);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(page.getContent()).hasSize(7);
        assertThat(page.similarAnnouncements()).isEmpty();
        assertThat(reads).hasSize(1);
    }

    @Test
    @DisplayName("page 2 → pas de bloc 2")
    void similarBlock_onlyOnFirstPage() {
        stubFindAll(trips(traveler(), 3, 11));
        Q q = new Q();
        q.from = TODAY.plusDays(10);
        q.pageable = PageRequest.of(1, 2);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.similarAnnouncements()).isEmpty();
        assertThat(reads).hasSize(1);
    }

    @Test
    @DisplayName("bloc 2 plafonné à 20 annonces")
    void similarBlock_cappedAt20() {
        stubFindAll(List.of(), trips(traveler(), 25, 30));
        Q q = new Q();
        q.to = TODAY.plusDays(10);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(page.getContent()).isEmpty();
        assertThat(page.similarAnnouncements()).hasSize(20);
        assertThat(page.similarAnnouncements()).allSatisfy(s -> assertThat(s.unmet()).isNotEmpty());
    }

    @Test
    @DisplayName("bloc 2 : moins d'écarts d'abord, même devant un meilleur score")
    void similarBlock_orderedByFewerMisses() {
        AnnouncementEntity twoMisses = trip(greatTraveler(), 30);
        twoMisses.setAvailableKg(BigDecimal.valueOf(3));
        AnnouncementEntity oneMiss = trip(badTraveler(), 30);
        stubFindAll(List.of(), List.of(twoMisses, oneMiss));
        Q q = new Q();
        q.to = TODAY.plusDays(10);
        q.minKg = BigDecimal.valueOf(8);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(similarIds(page)).containsExactly(oneMiss.getId(), twoMisses.getId());
        assertThat(page.similarAnnouncements().get(1).unmet()).extracting(UnmetCriterionDto::code)
                .containsExactly("DATE", "AVAILABLE_KG");
    }

    @Test
    @DisplayName("aucune ville → pas de bloc 2")
    void noCity_noSimilarBlock() {
        stubFindAll(List.of());
        Q q = new Q();
        q.dep = null;
        q.arr = "  ";
        q.to = TODAY.plusDays(10);

        assertThat(q.run().similarAnnouncements()).isEmpty();
        assertThat(reads).hasSize(1);
    }

    @Test
    @DisplayName("une ville suffit à déclencher le bloc 2")
    void oneCity_triggersSimilarBlock() {
        AnnouncementEntity offDate = trip(traveler(), 30);
        stubFindAll(List.of(), List.of(offDate));
        Q q = new Q();
        q.arr = null;
        q.to = TODAY.plusDays(10);

        assertThat(similarIds(q.run())).containsExactly(offDate.getId());
    }

    // ─── Filtres stricts : jamais relâchés dans le bloc 2 ────────────────────

    @Test
    @DisplayName("kycVerifiedOnly reste strict dans le vivier du bloc 2 ; les dates, elles, sont relâchées")
    void kycVerifiedOnly_staysStrict() {
        stubFindAll(List.of(), List.of());
        Q q = new Q();
        q.kyc = true;
        q.from = TODAY.plusDays(10);

        q.run();

        Probe pool = Probe.of(capturedSpecs(2).get(1));
        verify(pool.cb(), atLeastOnce()).equal(any(Expression.class), eq(KycStatus.VERIFIED));
        // Date saisie relâchée : seule reste la borne « pas encore parti » (aujourd'hui).
        verify(pool.cb(), never()).greaterThanOrEqualTo(any(Expression.class), eq(TODAY.plusDays(10)));
        Probe exact = Probe.of(capturedSpecs(2).get(0));
        verify(exact.root(), atLeastOnce()).get("departureDate");
    }

    @Test
    @DisplayName("maxPricePerKg reste strict dans le vivier du bloc 2")
    void maxPricePerKg_staysStrict() {
        when(exchangeRateService.toEurPivot(BigDecimal.TEN, "EUR")).thenReturn(BigDecimal.TEN);
        stubFindAll(List.of(), List.of());
        Q q = new Q();
        q.maxPrice = BigDecimal.TEN;

        q.run();

        Probe pool = Probe.of(capturedSpecs(2).get(1));
        verify(pool.root(), atLeastOnce()).get("pricePerKgEur");
    }

    @Test
    @DisplayName("le filtre moyens de paiement reste strict dans le vivier du bloc 2")
    void paymentMethods_staysStrict() {
        stubFindAll(List.of(), List.of());
        Q q = new Q();
        q.payments = Set.of(PaymentMethod.CASH);

        q.run();

        Probe pool = Probe.of(capturedSpecs(2).get(1));
        verify(pool.root(), atLeastOnce()).get("acceptedPaymentMethods");
    }

    @Test
    @DisplayName("le vivier du bloc 2 exclut les voyageurs bloqués (dans les deux sens)")
    void similarBlock_excludesBlockedTravelers() {
        UserEntity viewer = traveler();
        when(userRepository.findByFirebaseUid(viewer.getFirebaseUid())).thenReturn(Optional.of(viewer));
        stubFindAll(List.of(), List.of());
        Q q = new Q();
        q.viewer = viewer.getFirebaseUid();
        q.to = TODAY.plusDays(10);

        q.run();

        Probe pool = Probe.of(capturedSpecs(2).get(1));
        verify(pool.query().subquery(UUID.class), times(2)).from(UserBlockEntity.class);
    }

    // ─── Tri manuel ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("tri par prix : remplace le score dans chaque bloc, les blocs restent séparés")
    void manualPriceSort_keepsBlocksSeparated() {
        UserEntity t = traveler();
        AnnouncementEntity exact = trip(t, 11);
        exact.setPricePerKgEur(BigDecimal.valueOf(20));
        AnnouncementEntity dear = trip(greatTraveler(), 30);
        dear.setPricePerKgEur(BigDecimal.valueOf(9));
        AnnouncementEntity cheap = trip(t, 30);
        cheap.setPricePerKgEur(BigDecimal.valueOf(3));
        stubFindAll(List.of(exact), List.of(exact, dear, cheap));
        Q q = new Q();
        q.sortBy = "price";
        q.to = TODAY.plusDays(12);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(ids(page)).containsExactly(exact.getId());
        assertThat(similarIds(page)).containsExactly(cheap.getId(), dear.getId());
        // Page exacte triée en SQL, puis vivier du bloc 2 en lignes légères.
        assertThat(reads).extracting(Read::rows).containsExactly(false, true);
        assertThat(reads.get(0).pageable().getSort()).extracting(o -> o.getProperty())
                .containsExactly("pricePerKgEur", "travelerIsGpPro", "id");
    }

    @Test
    @DisplayName("tri par date décroissante dans le bloc 2")
    void manualDateSortDesc_ordersSimilarBlock() {
        UserEntity t = traveler();
        AnnouncementEntity d30 = trip(t, 30);
        AnnouncementEntity d40 = trip(t, 40);
        stubFindAll(List.of(), List.of(d30, d40));
        Q q = new Q();
        q.sortBy = "date";
        q.sortDir = "desc";
        q.to = TODAY.plusDays(12);

        assertThat(similarIds(q.run())).containsExactly(d40.getId(), d30.getId());
    }

    @Test
    @DisplayName("tri par date : les exactes absentes de la page sont relues pour être exclues du bloc 2")
    void manualDateSort_exactNotOnPage_excludedFromSimilar() {
        UserEntity t = traveler();
        AnnouncementEntity e1 = trip(t, 11);
        AnnouncementEntity e2 = trip(t, 11);
        AnnouncementEntity e3 = trip(t, 11);
        AnnouncementEntity offDate = trip(t, 30);
        // Page de taille 2 : e3 n'est pas sur la page.
        stubReads(List.of(
                new PageImpl<>(List.of(e1, e2), PageRequest.of(0, 2), 3),
                new PageImpl<>(List.of(e1, e2, e3)),
                new PageImpl<>(List.of(e1, e2, e3, offDate))));
        Q q = new Q();
        q.sortBy = "date";
        q.to = TODAY.plusDays(12);
        q.pageable = PageRequest.of(0, 2);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(similarIds(page)).containsExactly(offDate.getId());
    }

    // ─── Robustesse ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("voyageur sans note, ou introuvable → aucun NPE, écart MIN_RATING")
    void travelerWithoutRating_noNpe() {
        AnnouncementEntity unrated = trip(traveler(), 11);
        UserEntity ghost = new UserEntity();
        setId(ghost, UUID.randomUUID()); // absent du dépôt
        AnnouncementEntity orphan = trip(ghost, 11);
        stubFindAll(List.of(), List.of(unrated, orphan));
        Q q = new Q();
        q.minRating = new BigDecimal("4");

        List<TripSearchPage<AnnouncementSearchResponse>> holder = new ArrayList<>();
        assertThatNoException().isThrownBy(() -> holder.add(q.run()));
        TripSearchPage<AnnouncementSearchResponse> page = holder.get(0);
        assertThat(page.similarAnnouncements()).hasSize(2);
        assertThat(page.similarAnnouncements()).flatExtracting(SimilarAnnouncementResponse::unmet)
                .containsOnly(new UnmetCriterionDto("MIN_RATING", "4", ""));
    }

    @Test
    @DisplayName("includeFull : trajets complets après les réservables, chacun classé par score")
    void includeFull_fullTripsAfterBookable() {
        AnnouncementEntity fullGreat = trip(greatTraveler(), 5);
        fullGreat.setStatus(AnnouncementStatus.FULL);
        AnnouncementEntity bookableBad = trip(badTraveler(), 5);
        AnnouncementEntity bookableGreat = trip(greatTraveler(), 5);
        stubFindAll(List.of(bookableBad, bookableGreat, fullGreat));
        when(announcementRepository.count(ArgumentMatchers.<Specification<AnnouncementEntity>>any())).thenReturn(2L);
        Q q = new Q();
        q.dep = null;
        q.arr = null;
        q.includeFull = true;

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(ids(page)).containsExactly(bookableGreat.getId(), bookableBad.getId(), fullGreat.getId());
        assertThat(page.bookableElements()).isEqualTo(2L);
        assertThat(page.getTotalElements()).isEqualTo(3);
    }

    @Test
    @DisplayName("includeFull : le bloc 2 ne compte que les réservables exactes et ne contient que des ACTIVE")
    void includeFull_similarBlockBasedOnBookableOnly() {
        UserEntity t = traveler();
        AnnouncementEntity bookable = trip(t, 11);
        AnnouncementEntity offDate = trip(t, 30);
        stubFindAll(List.of(bookable), List.of(bookable, offDate));
        when(announcementRepository.count(ArgumentMatchers.<Specification<AnnouncementEntity>>any())).thenReturn(1L);
        Q q = new Q();
        q.includeFull = true;
        q.to = TODAY.plusDays(12);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(similarIds(page)).containsExactly(offDate.getId());
        assertThat(page.bookableElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("sans includeFull : bookableElements absent")
    void withoutIncludeFull_bookableElementsNull() {
        stubFindAll(List.of());
        Q q = new Q();
        q.dep = null;
        q.arr = null;

        assertThat(q.run().bookableElements()).isNull();
    }

    // ─── Appel compteur (size <= 1) ───────────────────────────────────────────

    @Test
    @DisplayName("appel compteur size=1 : chemin SQL simple, ni score ni bloc 2, total exact")
    void counterCall_sizeOne_skipsScoringAndSimilarBlock() {
        UserEntity t = traveler();
        AnnouncementEntity e1 = trip(t, 11);
        when(announcementRepository.findAll(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(e1), PageRequest.of(0, 1), 3));
        Q q = new Q();
        q.to = TODAY.plusDays(12);
        q.pageable = PageRequest.of(0, 1);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(ids(page)).containsExactly(e1.getId());
        assertThat(page.similarAnnouncements()).isEmpty();
        assertThat(page.bookableElements()).isNull();
        // Une seule lecture, de la taille demandée, triée par date en SQL.
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(announcementRepository, times(1)).findAll(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
        assertThat(pageable.getValue().getSort()).extracting(o -> o.getProperty())
                .containsExactly("departureDate", "travelerIsGpPro", "id");
        verify(scoreCalculator, never()).score(any(), ArgumentMatchers.anyBoolean(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong());
        // Seul le voyageur de la ligne servie est lu, jamais un lot de candidats.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<UUID>> travelerIds = ArgumentCaptor.forClass(Iterable.class);
        verify(userRepository, atLeastOnce()).findAllById(travelerIds.capture());
        assertThat(travelerIds.getAllValues()).allSatisfy(ids -> assertThat(ids).hasSizeLessThanOrEqualTo(1));
    }

    @Test
    @DisplayName("appel compteur size=1 avec includeFull : bookableElements et total exacts, pas de bloc 2")
    void counterCall_includeFull_keepsBookableCount() {
        AnnouncementEntity e1 = trip(traveler(), 11);
        when(announcementRepository.findAll(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(e1), PageRequest.of(0, 1), 4));
        when(announcementRepository.count(ArgumentMatchers.<Specification<AnnouncementEntity>>any())).thenReturn(2L);
        Q q = new Q();
        q.includeFull = true;
        q.pageable = PageRequest.of(0, 1);

        TripSearchPage<AnnouncementSearchResponse> page = q.run();

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.bookableElements()).isEqualTo(2L);
        assertThat(page.similarAnnouncements()).isEmpty();
        verify(announcementRepository, times(1)).findAll(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class));
        verify(scoreCalculator, never()).score(any(), ArgumentMatchers.anyBoolean(),
                ArgumentMatchers.anyLong(), ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("appel compteur size=1 avec tri par prix : le tri manuel demandé est gardé")
    void counterCall_keepsManualSort() {
        stubFindAll(List.of());
        Q q = new Q();
        q.sortBy = "price";
        q.pageable = PageRequest.of(0, 1);

        q.run();

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(announcementRepository, times(1)).findAll(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("pricePerKgEur")).isNotNull();
    }

    // ─── Bloc 2 : trajets déjà partis ─────────────────────────────────────────

    @Test
    @DisplayName("le vivier du bloc 2 exclut les trajets déjà partis (départ >= aujourd'hui UTC)")
    void similarBlock_excludesDepartedTrips() {
        stubFindAll(List.of(), List.of());
        Q q = new Q();
        q.to = TODAY.plusDays(10);

        q.run();

        Probe pool = Probe.of(capturedSpecs(2).get(1));
        verify(pool.cb()).greaterThanOrEqualTo(any(Expression.class), eq(TODAY));
    }

    // ─── Bloc 2 : départage GP Pro du tri manuel ──────────────────────────────

    @Test
    @DisplayName("tri manuel du bloc 2 : à date égale, le voyageur GP Pro passe avant (asc et desc)")
    void manualSort_similarBlock_gpProTieBreak() {
        for (String dir : List.of("asc", "desc")) {
            org.mockito.Mockito.reset(announcementRepository);
            stubFindAllById();
            UserEntity t = traveler();
            AnnouncementEntity plain = trip(t, 30);
            reId(plain, new UUID(0, 1));
            AnnouncementEntity gpPro = trip(t, 30);
            reId(gpPro, new UUID(0, 2));
            gpPro.setTravelerIsGpPro(true);
            stubFindAll(List.of(), List.of(plain, gpPro));
            Q q = new Q();
            q.sortBy = "date";
            q.sortDir = dir;
            q.to = TODAY.plusDays(12);

            assertThat(similarIds(q.run())).as(dir).containsExactly(gpPro.getId(), plain.getId());
        }
    }
}
