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
 * Fin de la mise en avant « pro d'abord » dans {@link AnnouncementService#searchAnnouncements}
 * (spec 2026-10-21-classement-annonces). Le lot 4 (SDD 2026-08-27-lot4-mise-en-avant-pro)
 * plaçait {@code travelerIsPro DESC} en tête de tout tri ; cette clé disparaît : le tri
 * manuel (date, prix) mène directement, et le tri par défaut suit le score interne, où le
 * statut Pro ne donne aucune avance. Ces tests remplacent ceux qui prouvaient l'ancienne
 * priorité, avec les mêmes discriminants retournés.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService.searchAnnouncements — plus de priorité aux voyageurs PRO")
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
                packageRequestRepository, negotiationThreadRepository, notificationDispatcher,
                org.mockito.Mockito.mock(com.yadony.api.common.BlockVisibility.class),
                org.mockito.Mockito.mock(AnnouncementViewService.class),
                org.mockito.Mockito.mock(TravelerPayoutNetworksPort.class),
                new com.yadony.api.matching.ranking.TripRankingProperties(null, 0, 0, 0, 0),
                new com.yadony.api.matching.ranking.TripScoreCalculator(
                        new com.yadony.api.matching.ranking.TripRankingProperties(null, 0, 0, 0, 0)),
                org.mockito.Mockito.mock(TravelerParcelsNetPort.class));
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

    // ─── Tri manuel : plus de clé travelerIsPro dans le Sort SQL ─────────────

    /**
     * Le tri par prix reste délégué au SQL sur le pivot EUR ; le {@link Sort} transmis au
     * repository commence désormais par ce pivot. Remettre la priorité PRO dans
     * {@code buildSort} ferait réapparaître {@code travelerIsPro} en tête et échouer
     * l'assertion.
     */
    @Test
    @DisplayName("tri par prix : le Sort SQL commence par le pivot EUR, sans travelerIsPro")
    void searchAnnouncements_sortByPrice_noLongerPutsProFirst() {
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
                .containsExactly("pricePerKgEur", "travelerIsGpPro", "id");
        assertThat(orders.get(0).isAscending()).isTrue();
    }

    /** Même contrat pour le tri par date : le critère choisi mène, le GP Pro ne fait que départager. */
    @Test
    @DisplayName("tri par date : le Sort SQL commence par departureDate, sans travelerIsPro")
    void searchAnnouncements_sortByDate_noLongerPutsProFirst() {
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
        assertThat(orders).extracting(Sort.Order::getProperty)
                .containsExactly("departureDate", "travelerIsGpPro", "id");
        assertThat(orders.get(0).getDirection()).isEqualTo(Sort.Direction.ASC);
        assertThat(orders.get(1).getDirection()).isEqualTo(Sort.Direction.DESC);
        assertThat(orders.get(2).getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    /**
     * Tri par défaut (score) : un trajet {@code travelerIsPro} d'un voyageur peu fiable passe
     * APRÈS le trajet mieux classé d'un voyageur non Pro. Discriminant : la base renvoie le
     * trajet Pro en premier ; l'ancienne priorité PRO l'aurait gardé en tête.
     */
    @Test
    @DisplayName("tri par défaut : travelerIsPro ne devance plus un trajet mieux classé")
    void searchAnnouncements_defaultScore_proDoesNotOutrankBetterScore() {
        UserEntity weakPro = buildTraveler();
        weakPro.setCancellationCount(4);
        weakPro.setNoShowCount(2);
        UserEntity strong = buildTraveler();
        setId(strong, UUID.randomUUID());
        strong.setTotalDeliveries(60);
        strong.setAverageRating(new BigDecimal("4.9"));
        strong.setRatingCount(30);
        AnnouncementEntity pro = buildAnnouncement(weakPro);
        setId(pro, UUID.randomUUID());
        pro.setTravelerIsPro(true);
        AnnouncementEntity better = buildAnnouncement(strong);
        setId(better, UUID.randomUUID());

        // Tri par score : candidats en lignes légères, puis entités de la page seules.
        when(announcementRepository.findRankingRows(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), ArgumentMatchers.any(Pageable.class)))
                .thenReturn(List.of(RankingRow.of(pro), RankingRow.of(better)));
        when(announcementRepository.findAllById(ArgumentMatchers.any())).thenReturn(List.of(pro, better));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(weakPro, strong));
        when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(List.of());

        Page<AnnouncementSearchResponse> page = search(null, "asc");

        assertThat(page.getContent()).extracting(AnnouncementSearchResponse::id)
                .containsExactly(better.getId(), pro.getId());
    }
}
