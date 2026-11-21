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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Compteur et état « j'aime » dans la recherche : un seul appel groupé par page. */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService.withFreshLikes — likeCount et likedByMe")
class AnnouncementServiceLikesTest {

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

    @Mock private com.yadony.api.likes.TripLikeService tripLikeService;

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
                org.mockito.Mockito.mock(TravelerParcelsNetPort.class),
                org.mockito.Mockito.mock(com.yadony.api.matching.TransportModeGuard.class),
                tripLikeService);
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
        setId(a, UUID.randomUUID());
        return a;
    }

    private Page<AnnouncementSearchResponse> searchAs(String viewerUid, AnnouncementEntity... trips) {
        when(announcementRepository.findAll(
                ArgumentMatchers.<Specification<AnnouncementEntity>>any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(trips)));
        lenient().when(userRepository.findAllById(anyCollection())).thenReturn(List.of(buildTraveler()));
        lenient().when(bidRepository.countVisibleByAnnouncementIds(anyCollection())).thenReturn(List.of());
        return announcementService.searchAnnouncements(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                "date", "asc", PageRequest.of(0, 10), viewerUid, null);
    }

    private Page<AnnouncementSearchResponse> freshAs(String viewerUid, AnnouncementEntity... trips) {
        return announcementService.withFreshLikes(searchAs(viewerUid, trips), viewerUid);
    }

    @Test
    @DisplayName("la recherche elle-même ne calcule plus les j'aime (page mise en cache : 0 / faux)")
    void search_itselfDoesNotComputeLikes() {
        AnnouncementEntity trip = buildAnnouncement(buildTraveler());

        Page<AnnouncementSearchResponse> page = searchAs(null, trip);

        assertThat(page.getContent().get(0).likeCount()).isZero();
        assertThat(page.getContent().get(0).likedByMe()).isFalse();
        verify(tripLikeService, never()).countsFor(anyCollection());
        verify(tripLikeService, never()).likedBy(any(), anyCollection());
    }

    @Test
    @DisplayName("lecteur connecté : likeCount pour tous, likedByMe vrai seulement sur ses j'aime")
    void fresh_connectedViewer_likeCountAndLikedByMe() {
        AnnouncementEntity liked = buildAnnouncement(buildTraveler());
        AnnouncementEntity other = buildAnnouncement(buildTraveler());
        UUID viewerId = UUID.randomUUID();
        when(userRepository.findIdByFirebaseUid("uid-viewer")).thenReturn(Optional.of(viewerId));
        when(tripLikeService.countsFor(anyCollection()))
                .thenReturn(Map.of(liked.getId(), 3L));
        when(tripLikeService.likedBy(eq(viewerId), anyCollection())).thenReturn(Set.of(liked.getId()));

        Page<AnnouncementSearchResponse> page = freshAs("uid-viewer", liked, other);

        AnnouncementSearchResponse r1 = page.getContent().stream().filter(r -> r.id().equals(liked.getId())).findFirst().orElseThrow();
        AnnouncementSearchResponse r2 = page.getContent().stream().filter(r -> r.id().equals(other.getId())).findFirst().orElseThrow();
        assertThat(r1.likeCount()).isEqualTo(3L);
        assertThat(r1.likedByMe()).isTrue();
        assertThat(r2.likeCount()).isZero();
        assertThat(r2.likedByMe()).isFalse();
    }

    @Test
    @DisplayName("lecteur anonyme : likeCount rendu, likedByMe faux, aucun appel à likedBy ni lecture d'utilisateur")
    void fresh_anonymousViewer_likedByMeFalse() {
        AnnouncementEntity trip = buildAnnouncement(buildTraveler());
        when(tripLikeService.countsFor(anyCollection())).thenReturn(Map.of(trip.getId(), 5L));

        Page<AnnouncementSearchResponse> page = freshAs(null, trip);

        assertThat(page.getContent().get(0).likeCount()).isEqualTo(5L);
        assertThat(page.getContent().get(0).likedByMe()).isFalse();
        verify(tripLikeService, never()).likedBy(any(), anyCollection());
        verify(userRepository, never()).findIdByFirebaseUid(any());
    }

    @Test
    @DisplayName("un seul appel groupé par page, quel que soit le nombre de trajets")
    void fresh_oneBatchedCallPerPage() {
        AnnouncementEntity a = buildAnnouncement(buildTraveler());
        AnnouncementEntity b = buildAnnouncement(buildTraveler());
        AnnouncementEntity c = buildAnnouncement(buildTraveler());
        when(userRepository.findIdByFirebaseUid("uid-viewer")).thenReturn(Optional.of(UUID.randomUUID()));
        when(tripLikeService.countsFor(anyCollection())).thenReturn(Map.of());
        when(tripLikeService.likedBy(any(), anyCollection())).thenReturn(Set.of());

        freshAs("uid-viewer", a, b, c);

        verify(tripLikeService, times(1)).countsFor(anyCollection());
        verify(tripLikeService, times(1)).likedBy(any(), anyCollection());
    }
}
