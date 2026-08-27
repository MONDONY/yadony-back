package com.yadony.api.matching;

import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateService;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Couvre la mise en avant des voyageurs PRO dans {@link AnnouncementService#searchAnnouncements},
 * absente de {@link AnnouncementServiceTest} avant ce lot (Task 1 — SDD
 * 2026-08-27-lot4-mise-en-avant-pro).
 *
 * <p>Le seul test d'ordre préexistant ({@code searchAnnouncements_sortByPriceAsc_...}) fait
 * partager le même voyageur — donc le même {@code travelerIsPro} — à ses trois annonces : il
 * prouve la conversion multidevise, jamais la priorité PRO. Chaque test ci-dessous mélange au
 * contraire des annonces de statut PRO différent, choisies pour qu'un tri ignorant ce statut
 * produirait un ordre différent de celui attendu — voir la javadoc de chaque test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService.searchAnnouncements — mise en avant des voyageurs PRO")
class AnnouncementProPriorityTest {

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

    private AnnouncementService announcementService;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void stubDefaultActiveCurrency() {
        lenient().when(activeCurrencyResolver.resolve(ArgumentMatchers.any())).thenReturn("EUR");
        // Repli neutre : conversion identité tant qu'un test ne stub pas un taux explicite.
        lenient().when(exchangeRateService.convert(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @BeforeEach
    void initService() {
        YadonyConfigProperties config = new YadonyConfigProperties(null, null,
                new YadonyConfigProperties.Urgency(3), null);
        lenient().when(storageService.avatarUrl(any())).thenAnswer(inv -> inv.getArgument(0));
        AnnouncementSearchMapper realMapper = new AnnouncementSearchMapper(
                userRepository, bidRepository, priceGridService, storageService,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3));
        announcementService = new AnnouncementService(
                announcementRepository, bidRepository, userRepository,
                auditService, eventPublisher, config,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3),
                priceGridService, flagService,
                storageService, favoriteRepository, activeCurrencyResolver, exchangeRateService, realMapper,
                packageRequestRepository, negotiationThreadRepository, notificationDispatcher);
    }

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

    private UserEntity buildTraveler() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-traveler-001");
        u.getRoles().add(Role.TRAVELER);
        setId(u, USER_ID);
        return u;
    }

    /** Calqué sur {@code AnnouncementServiceTest#buildAnnouncement} : mêmes champs obligatoires. */
    private AnnouncementEntity buildAnnouncement(UserEntity traveler) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG Terminal 2E");
        a.setPickupLat(BigDecimal.valueOf(49.009));
        a.setPickupLng(BigDecimal.valueOf(2.547));
        a.setDeliveryAddressLabel("Aéroport LSS");
        a.setDeliveryLat(BigDecimal.valueOf(14.739));
        a.setDeliveryLng(BigDecimal.valueOf(-17.490));
        return a;
    }

    private Page<AnnouncementSearchResponse> search(String sortBy, String sortDir) {
        return announcementService.searchAnnouncements(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                sortBy, sortDir, PageRequest.of(0, 10), null, null);
    }

    // ─── Branche "prix" (comparateur en mémoire) ──────────────────────────────

    /**
     * Le test le plus parlant du lot : une annonce PRO plus chère doit malgré tout ressortir
     * avant une annonce standard moins chère, en tri par prix CROISSANT.
     *
     * <p><b>Discriminant :</b> en brut, un tri ascendant classerait {@code standardCheap} (5)
     * avant {@code proExpensive} (100). Si {@code Comparator.comparing(isTravelerIsPro).reversed()}
     * était retiré du comparateur, l'ordre obtenu serait exactement l'inverse de celui attendu
     * ici — le test échouerait.
     */
    @Test
    @DisplayName("une annonce PRO plus chère passe devant une annonce standard moins chère (tri prix croissant)")
    void searchAnnouncements_sortByPriceAsc_proAnnouncementOutranksCheaperStandardOne() {
        UserEntity traveler = buildTraveler();

        AnnouncementEntity proExpensive = buildAnnouncement(traveler);
        UUID proId = UUID.randomUUID();
        setId(proExpensive, proId);
        proExpensive.setTravelerIsPro(true);
        proExpensive.setCurrency("EUR");
        proExpensive.setPricePerKg(BigDecimal.valueOf(100));

        AnnouncementEntity standardCheap = buildAnnouncement(traveler);
        UUID standardId = UUID.randomUUID();
        setId(standardCheap, standardId);
        standardCheap.setTravelerIsPro(false);
        standardCheap.setCurrency("EUR");
        standardCheap.setPricePerKg(BigDecimal.valueOf(5));

        // Ordre reçu du repository volontairement "correct au brut" (cheap d'abord) pour bien
        // montrer que c'est le service, pas un artefact d'ordre d'entrée, qui inverse.
        when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any()))
                .thenReturn(List.of(standardCheap, proExpensive));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(traveler));
        when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(List.of());

        Page<AnnouncementSearchResponse> result = search("price", "asc");

        assertThat(result.getContent()).extracting(AnnouncementSearchResponse::id)
                .containsExactly(proId, standardId);
    }

    /**
     * À statut PRO égal, le critère demandé (prix) doit départager normalement les annonces
     * PRO entre elles — et l'ensemble doit rester devant l'annonce standard, même moins chère.
     *
     * <p><b>Discriminant (double) :</b>
     * <ul>
     *   <li>Entre {@code proCheap} (5) et {@code proExpensive} (50), le premier terme du
     *   comparateur ({@code isTravelerIsPro}) est à égalité (les deux valent {@code true}) :
     *   seul {@code thenComparing(byConvertedPrice)} peut les départager. Un comparateur qui
     *   ignorerait ce second terme (ex. le remplacerait par un no-op, ou l'appliquerait dans le
     *   mauvais sens) romprait cet ordre sans que le premier test de ce fichier ne le révèle,
     *   puisque celui-ci n'oppose jamais deux PRO entre eux.</li>
     *   <li>Si la priorité PRO elle-même disparaissait, le tri ascendant brut classerait
     *   {@code standardCheapest} (1) en tête, devant les deux PRO — contredisant l'ordre attendu
     *   ici où il ressort en dernier.</li>
     * </ul>
     */
    @Test
    @DisplayName("à statut PRO égal, le critère de tri demandé départage normalement les annonces PRO entre elles")
    void searchAnnouncements_twoProAnnouncementsTiedOnProStatus_orderedByRequestedCriterion() {
        UserEntity traveler = buildTraveler();

        AnnouncementEntity proCheap = buildAnnouncement(traveler);
        setId(proCheap, UUID.randomUUID());
        proCheap.setTravelerIsPro(true);
        proCheap.setCurrency("EUR");
        proCheap.setPricePerKg(BigDecimal.valueOf(5));

        AnnouncementEntity proExpensive = buildAnnouncement(traveler);
        setId(proExpensive, UUID.randomUUID());
        proExpensive.setTravelerIsPro(true);
        proExpensive.setCurrency("EUR");
        proExpensive.setPricePerKg(BigDecimal.valueOf(50));

        AnnouncementEntity standardCheapest = buildAnnouncement(traveler);
        setId(standardCheapest, UUID.randomUUID());
        standardCheapest.setTravelerIsPro(false);
        standardCheapest.setCurrency("EUR");
        standardCheapest.setPricePerKg(BigDecimal.valueOf(1));

        when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any()))
                .thenReturn(List.of(proExpensive, standardCheapest, proCheap));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(traveler));
        when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(List.of());

        Page<AnnouncementSearchResponse> result = search("price", "asc");

        assertThat(result.getContent()).extracting(AnnouncementSearchResponse::id)
                .containsExactly(proCheap.getId(), proExpensive.getId(), standardCheapest.getId());
    }

    /**
     * Stabilité de l'ordre : deux annonces PRO à prix converti IDENTIQUE doivent toujours
     * sortir dans le même ordre (par id croissant), quel que soit l'ordre dans lequel le
     * repository les renvoie.
     *
     * <p><b>Discriminant (double) :</b>
     * <ul>
     *   <li>Le repository renvoie délibérément {@code proHigh} (id le plus grand) AVANT
     *   {@code proLow} (id le plus petit). Sur ces deux entrées, {@code isTravelerIsPro} et le
     *   prix converti sont à égalité : seul {@code thenComparing(getId)} peut encore les
     *   départager. {@code Stream.sorted} étant un tri stable, retirer ce dernier maillon du
     *   comparateur laisserait l'ordre d'entrée inchangé — {@code proHigh} sortirait avant
     *   {@code proLow}, contredisant l'ordre par id croissant attendu ici.</li>
     *   <li>{@code standardCheapest} (prix le plus bas de tous) doit malgré tout ressortir en
     *   dernier : si la priorité PRO disparaissait, le tri ascendant brut le placerait en tête.</li>
     * </ul>
     */
    @Test
    @DisplayName("deux annonces PRO à prix converti identique sortent dans un ordre stable (départagées par id), indépendamment de l'ordre reçu du repository")
    void searchAnnouncements_tieOnProStatusAndPrice_ordersDeterministicallyById() {
        UserEntity traveler = buildTraveler();

        UUID lowId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID highId = UUID.fromString("00000000-0000-0000-0000-000000000002");

        AnnouncementEntity proHigh = buildAnnouncement(traveler);
        setId(proHigh, highId);
        proHigh.setTravelerIsPro(true);
        proHigh.setCurrency("EUR");
        proHigh.setPricePerKg(BigDecimal.valueOf(10));

        AnnouncementEntity proLow = buildAnnouncement(traveler);
        setId(proLow, lowId);
        proLow.setTravelerIsPro(true);
        proLow.setCurrency("EUR");
        proLow.setPricePerKg(BigDecimal.valueOf(10));

        AnnouncementEntity standardCheapest = buildAnnouncement(traveler);
        UUID standardId = UUID.randomUUID();
        setId(standardCheapest, standardId);
        standardCheapest.setTravelerIsPro(false);
        standardCheapest.setCurrency("EUR");
        standardCheapest.setPricePerKg(BigDecimal.valueOf(1));

        // Ordre d'entrée : standard d'abord, puis proHigh AVANT proLow — l'inverse de l'ordre
        // par id attendu pour la paire PRO.
        when(announcementRepository.findAll(ArgumentMatchers.<Specification<AnnouncementEntity>>any()))
                .thenReturn(List.of(standardCheapest, proHigh, proLow));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(traveler));
        when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(List.of());

        Page<AnnouncementSearchResponse> result = search("price", "asc");

        assertThat(result.getContent()).extracting(AnnouncementSearchResponse::id)
                .containsExactly(lowId, highId, standardId);
    }

    // ─── Branche "date" (Sort SQL construit par buildSort, privée) ────────────

    /**
     * Le tri par date étant le défaut exécuté en SQL, {@code buildSort} n'est pas accessible
     * directement (privée, jamais rendue visible pour ce test) : on capture donc le
     * {@link Pageable} réellement transmis au repository et on inspecte son {@link Sort}.
     *
     * <p><b>Discriminant :</b> si {@code Sort.by(DESC, "travelerIsPro")} était retiré de
     * {@code buildSort}, le premier ordre du {@code Sort} capturé serait {@code departureDate}
     * ASC (le critère secondaire seul), pas {@code travelerIsPro} DESC — l'assertion sur
     * {@code orders.get(0)} échouerait.
     */
    @Test
    @DisplayName("tri par date (défaut, exécuté en base) → le Sort transmis au repository place travelerIsPro DESC avant le critère secondaire")
    void searchAnnouncements_defaultDateSort_buildsSqlSortWithTravelerIsProFirst() {
        UserEntity traveler = buildTraveler();
        AnnouncementEntity ann = buildAnnouncement(traveler);
        setId(ann, UUID.randomUUID());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(announcementRepository.findAll(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of(ann)));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(traveler));
        when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(List.of());

        search("date", "asc");

        List<Sort.Order> orders = pageableCaptor.getValue().getSort().stream().toList();

        assertThat(orders).hasSize(2);
        assertThat(orders.get(0).getProperty()).isEqualTo("travelerIsPro");
        assertThat(orders.get(0).getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(orders.get(1).getProperty()).isEqualTo("departureDate");
        assertThat(orders.get(1).getDirection()).isEqualTo(Sort.Direction.ASC);
    }
}
