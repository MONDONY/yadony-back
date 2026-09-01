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
        lenient().when(activeCurrencyResolver.resolveDisplay(ArgumentMatchers.any())).thenReturn("EUR");
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

    // ─── Branche "prix" (Sort SQL sur le pivot EUR depuis V235) ───────────────

    /**
     * Le tri par prix n'est plus un comparateur en mémoire : il est délégué au SQL
     * sur le pivot EUR ({@code price_per_kg_eur}). L'invariant PRO de ce fichier se
     * vérifie donc au même endroit que la branche date : le {@link Sort} réellement
     * transmis au repository doit mettre {@code travelerIsPro DESC} en PREMIER,
     * avant le critère demandé — retirer la priorité PRO de {@code buildSort}
     * ferait échouer cette assertion. Le pivot est en second, l'id en départage
     * pour un ordre total stable sous pagination.
     *
     * <p>La preuve d'ordre multidevise (payer 100 EUR classé après 5 EUR mais
     * avant 5000 XOF) n'a plus sa place en mock : multiplier le pivot par le taux
     * du lecteur préserve l'ordre, c'est la base qui trie.
     */
    @Test
    @DisplayName("tri par prix : le Sort SQL garde la priorité PRO en tête, pivot EUR ensuite, id en départage")
    void searchAnnouncements_sortByPrice_keepsProPriorityFirstInSqlSort() {
        UserEntity traveler = buildTraveler();
        AnnouncementEntity ann = buildAnnouncement(traveler);
        setId(ann, UUID.randomUUID());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        when(announcementRepository.findAll(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of(ann)));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(traveler));
        when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(List.of());

        search("price", "asc");

        List<Sort.Order> orders = pageableCaptor.getValue().getSort().stream().toList();
        assertThat(orders).extracting(Sort.Order::getProperty)
                .containsExactly("travelerIsPro", "pricePerKgEur", "id");
        assertThat(orders.get(0).getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(orders.get(1).isAscending()).isTrue();
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
