package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.likes.TripLikeService;
import com.yadony.api.matching.dto.AnnouncementSearchResponse;
import com.yadony.api.matching.dto.SimilarAnnouncementResponse;
import com.yadony.api.payments.currency.ExchangeRateEntity;
import com.yadony.api.payments.currency.ExchangeRateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * La page de recherche est mise en cache 5 min (clé par lecteur), mais l'état des j'aime
 * (compteur, « aimé par moi ») doit être relu à chaque requête : {@code withFreshLikes}
 * recolle des données fraîches sur la page en cache, sans jamais la modifier.
 */
@SpringBootTest
@ActiveProfiles("test")
class AnnouncementSearchFreshLikesIntegrationTest {

    @Autowired private AnnouncementService announcementService;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private CacheManager cacheManager;
    @Autowired private ExchangeRateRepository exchangeRateRepository;
    @MockitoSpyBean private TripLikeService tripLikeService;

    @BeforeEach
    void cleanDb() {
        cacheManager.getCache("announcements-search").clear();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        exchangeRateRepository.deleteAll();
        exchangeRateRepository.save(new ExchangeRateEntity("EUR", BigDecimal.ONE));
    }

    @Test
    void likeAddedBetweenTwoCachedSearches_isVisibleOnTheSecond() {
        UserEntity viewer = persistUser("viewer-" + UUID.randomUUID());
        UserEntity traveler = persistUser("traveler-" + UUID.randomUUID());
        UUID tripId = persistAnnouncement(traveler.getId(), 10);

        Page<AnnouncementSearchResponse> cached1 = search(viewer.getFirebaseUid());
        Page<AnnouncementSearchResponse> first = announcementService.withFreshLikes(cached1, viewer.getFirebaseUid());
        assertThat(first.getContent().get(0).likeCount()).isZero();
        assertThat(first.getContent().get(0).likedByMe()).isFalse();

        tripLikeService.like(viewer.getId(), tripId);

        Page<AnnouncementSearchResponse> cached2 = search(viewer.getFirebaseUid());
        // Preuve que la page vient bien du cache : même instance, état de j'aime périmé dedans.
        assertThat(cached2).isSameAs(cached1);
        assertThat(cached2.getContent().get(0).likeCount()).isZero();

        Page<AnnouncementSearchResponse> second = announcementService.withFreshLikes(cached2, viewer.getFirebaseUid());
        assertThat(second.getContent().get(0).likeCount()).isEqualTo(1);
        assertThat(second.getContent().get(0).likedByMe()).isTrue();
        // Le reste de la page est celui du cache, et l'objet en cache n'est pas modifié.
        assertThat(second.getContent().get(0).id()).isEqualTo(tripId);
        assertThat(cached2.getContent().get(0).likeCount()).isZero();
        assertThat(second.getTotalElements()).isEqualTo(cached2.getTotalElements());
    }

    @Test
    void unlike_isVisibleOnTheNextSearch() {
        UserEntity viewer = persistUser("viewer-" + UUID.randomUUID());
        UserEntity traveler = persistUser("traveler-" + UUID.randomUUID());
        UUID tripId = persistAnnouncement(traveler.getId(), 10);
        tripLikeService.like(viewer.getId(), tripId);
        search(viewer.getFirebaseUid());

        tripLikeService.unlike(viewer.getId(), tripId);

        AnnouncementSearchResponse item = announcementService
                .withFreshLikes(search(viewer.getFirebaseUid()), viewer.getFirebaseUid()).getContent().get(0);
        assertThat(item.likeCount()).isZero();
        assertThat(item.likedByMe()).isFalse();
    }

    @Test
    void oneCountAndOneLikedByCall_forContentAndSimilarTogether_andSimilarGetsTheOverlay() {
        UserEntity viewer = persistUser("viewer-" + UUID.randomUUID());
        UserEntity other = persistUser("other-" + UUID.randomUUID());
        UserEntity traveler = persistUser("traveler-" + UUID.randomUUID());
        UUID contentTrip = persistAnnouncement(traveler.getId(), 10);
        UUID similarTrip = persistAnnouncement(traveler.getId(), 11);
        tripLikeService.like(viewer.getId(), similarTrip);
        tripLikeService.like(other.getId(), similarTrip);
        tripLikeService.like(other.getId(), contentTrip);

        AnnouncementSearchResponse stale = search(viewer.getFirebaseUid()).getContent().stream()
                .filter(r -> r.id().equals(contentTrip)).findFirst().orElseThrow();
        AnnouncementSearchResponse staleSimilar = search(viewer.getFirebaseUid()).getContent().stream()
                .filter(r -> r.id().equals(similarTrip)).findFirst().orElseThrow()
                .withLikes(0, false);
        Page<AnnouncementSearchResponse> page = new TripSearchPage<>(
                List.of(stale.withLikes(0, false)), PageRequest.of(0, 20), 1, 1L,
                List.of(new SimilarAnnouncementResponse(staleSimilar, List.of())));
        clearInvocations(tripLikeService);

        TripSearchPage<AnnouncementSearchResponse> fresh = (TripSearchPage<AnnouncementSearchResponse>)
                announcementService.withFreshLikes(page, viewer.getFirebaseUid());

        verify(tripLikeService, times(1)).countsFor(any());
        verify(tripLikeService, times(1)).likedBy(any(), any());
        assertThat(fresh.getContent().get(0).likeCount()).isEqualTo(1);
        assertThat(fresh.getContent().get(0).likedByMe()).isFalse();
        AnnouncementSearchResponse sim = fresh.similarAnnouncements().get(0).announcement();
        assertThat(sim.likeCount()).isEqualTo(2);
        assertThat(sim.likedByMe()).isTrue();
        assertThat(fresh.bookableElements()).isEqualTo(1L);
        assertThat(page.getContent().get(0).likeCount()).isZero();
    }

    @Test
    void guestAndUnknownViewer_getTheCount_butNeverLikedByMe() {
        UserEntity liker = persistUser("liker-" + UUID.randomUUID());
        UserEntity traveler = persistUser("traveler-" + UUID.randomUUID());
        UUID tripId = persistAnnouncement(traveler.getId(), 10);
        tripLikeService.like(liker.getId(), tripId);
        Page<AnnouncementSearchResponse> cached = search(null);
        clearInvocations(tripLikeService);

        AnnouncementSearchResponse guest = announcementService.withFreshLikes(cached, null).getContent().get(0);
        AnnouncementSearchResponse unknown = announcementService.withFreshLikes(cached, "inconnu-uid").getContent().get(0);

        verify(tripLikeService, org.mockito.Mockito.never()).likedBy(any(), any());
        assertThat(guest.likeCount()).isEqualTo(1);
        assertThat(guest.likedByMe()).isFalse();
        assertThat(unknown.likeCount()).isEqualTo(1);
        assertThat(unknown.likedByMe()).isFalse();
    }

    @Test
    void emptyPage_makesNoLikeQuery() {
        UserEntity viewer = persistUser("viewer-" + UUID.randomUUID());
        Page<AnnouncementSearchResponse> empty = search(viewer.getFirebaseUid());
        clearInvocations(tripLikeService);

        assertThat(announcementService.withFreshLikes(empty, viewer.getFirebaseUid()).getContent()).isEmpty();
        verify(tripLikeService, times(0)).countsFor(any());
    }

    private Page<AnnouncementSearchResponse> search(String viewerFirebaseUid) {
        return announcementService.searchAnnouncements(
                "Abidjan", "Paris", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                "date", "asc", PageRequest.of(0, 20), viewerFirebaseUid, null);
    }

    private UserEntity persistUser(String firebaseUid) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setFirstName("Test");
        user.setLastName("Cache");
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        roles.add(Role.SENDER);
        user.setRoles(roles);
        return userRepository.save(user);
    }

    private UUID persistAnnouncement(UUID travelerId, int daysAhead) {
        AnnouncementEntity announcement = new AnnouncementEntity();
        announcement.setTravelerId(travelerId);
        announcement.setDepartureCity("Abidjan");
        announcement.setArrivalCity("Paris");
        announcement.setDepartureDate(LocalDate.now().plusDays(daysAhead));
        announcement.setDepartureTime(LocalTime.of(10, 0));
        announcement.setArrivalTime(LocalTime.of(18, 0));
        announcement.setTransportMode(TransportMode.PLANE);
        announcement.setPickupAddressLabel("Abidjan pickup");
        announcement.setPickupLat(new BigDecimal("5.345000"));
        announcement.setPickupLng(new BigDecimal("-4.024000"));
        announcement.setDeliveryAddressLabel("Paris Centre");
        announcement.setDeliveryLat(new BigDecimal("48.860000"));
        announcement.setDeliveryLng(new BigDecimal("2.350000"));
        announcement.setAvailableKg(new BigDecimal("10.00"));
        announcement.setTotalKg(new BigDecimal("10.00"));
        announcement.setPricePerKg(new BigDecimal("5.00"));
        announcement.setCurrency("EUR");
        announcement.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.save(announcement).getId();
    }
}
