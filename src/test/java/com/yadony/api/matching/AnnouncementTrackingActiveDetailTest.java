package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.BlockVisibility;
import com.yadony.api.common.StorageService;
import com.yadony.api.config.YadonyConfigProperties;
import com.yadony.api.favorites.FavoriteRepository;
import com.yadony.api.matching.dto.AnnouncementDetailResponse;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.payments.currency.ActiveCurrencyResolver;
import com.yadony.api.payments.currency.ExchangeRateService;
import com.yadony.api.requests.repository.NegotiationThreadRepository;
import com.yadony.api.requests.repository.PackageRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fenêtre de suivi ouverte, servie par le détail du trajet avec la même règle que la liste « Mes trajets »
 * ({@code TripPositionService.isWindowOpen}), au seul voyageur propriétaire.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementService.getAnnouncementDetail — trackingActive")
class AnnouncementTrackingActiveDetailTest {

    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();
    private static final UUID TRAVELER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final String TRAVELER_UID = "uid-traveler";
    private static final String OTHER_UID = "uid-other";

    @Mock private AnnouncementRepository announcementRepository;
    @Mock private BidRepository bidRepository;
    @Mock private UserRepository userRepository;
    @Mock private StorageService storageService;
    @Mock private BlockVisibility blockVisibility;
    @Mock private PriceGridService priceGridService;
    @Mock private TravelerPayoutNetworksPort payoutNetworksPort;
    @Mock private TravelerParcelsNetPort parcelsNetPort;

    private AnnouncementService service;
    private AnnouncementEntity announcement;

    @BeforeEach
    void init() {
        YadonyConfigProperties config = new YadonyConfigProperties(null, null,
                new YadonyConfigProperties.Urgency(3), null);
        service = new AnnouncementService(
                announcementRepository, bidRepository, userRepository,
                mock(AuditService.class), mock(ApplicationEventPublisher.class), config,
                com.yadony.api.config.PlatformSettingsTestFactory.withUrgencyThresholdDays(3),
                priceGridService,
                mock(com.yadony.api.country.FlagService.class),
                storageService,
                mock(FavoriteRepository.class),
                mock(ActiveCurrencyResolver.class),
                mock(ExchangeRateService.class),
                mock(AnnouncementSearchMapper.class),
                mock(PackageRequestRepository.class),
                mock(NegotiationThreadRepository.class),
                mock(NotificationDispatcher.class),
                blockVisibility, mock(AnnouncementViewService.class), payoutNetworksPort,
                new com.yadony.api.matching.ranking.TripRankingProperties(null, 0, 0, 0, 0),
                new com.yadony.api.matching.ranking.TripScoreCalculator(
                        new com.yadony.api.matching.ranking.TripRankingProperties(null, 0, 0, 0, 0)),
                parcelsNetPort);

        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANNOUNCEMENT_ID);
        a.setTravelerId(TRAVELER_ID);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.valueOf(20));
        a.setTotalKg(BigDecimal.valueOf(20));
        a.setPricePerKg(BigDecimal.valueOf(5));
        a.setCurrency("XOF");
        a.setStatus(AnnouncementStatus.ACTIVE);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("CDG");
        a.setPickupLat(BigDecimal.valueOf(49.009));
        a.setPickupLng(BigDecimal.valueOf(2.547));
        a.setDeliveryAddressLabel("LSS");
        a.setDeliveryLat(BigDecimal.valueOf(14.739));
        a.setDeliveryLng(BigDecimal.valueOf(-17.490));
        announcement = a;
        when(announcementRepository.findById(ANNOUNCEMENT_ID)).thenReturn(Optional.of(a));
        when(userRepository.findById(TRAVELER_ID)).thenReturn(Optional.of(new UserEntity()));
        user(TRAVELER_UID, TRAVELER_ID);
        user(OTHER_UID, OTHER_ID);
    }

    private void user(String uid, UUID id) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", id);
        u.setFirebaseUid(uid);
        org.mockito.Mockito.lenient().when(userRepository.findByFirebaseUid(uid)).thenReturn(Optional.of(u));
    }

    private void carriedParcel(boolean carried) {
        org.mockito.Mockito.lenient().when(bidRepository.existsByAnnouncementIdAndStatusIn(
                eq(ANNOUNCEMENT_ID), eq(com.yadony.api.tracking.TripPositionService.carriedStatuses())))
                .thenReturn(carried);
    }

    @Test
    @DisplayName("fenêtre ouverte (départ passé) et colis porté : true pour le voyageur")
    void trackingActive_windowOpenAndParcelCarried_isTrue() {
        announcement.setDepartureDate(LocalDate.now().minusDays(1));
        carriedParcel(true);

        assertThat(service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID).trackingActive()).isTrue();
    }

    @Test
    @DisplayName("fenêtre ouverte mais aucun colis porté : false")
    void trackingActive_windowOpenWithoutCarriedParcel_isFalse() {
        announcement.setDepartureDate(LocalDate.now().minusDays(1));
        carriedParcel(false);

        assertThat(service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID).trackingActive()).isFalse();
    }

    @Test
    @DisplayName("fenêtre fermée (départ dans 10 jours) : false même avec un colis porté")
    void trackingActive_windowClosed_isFalse() {
        carriedParcel(true);

        assertThat(service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID).trackingActive()).isFalse();
    }

    @Test
    @DisplayName("même règle que la liste : ouverte 30 min avant le départ, fermée avant")
    void trackingActive_opensThirtyMinutesBeforeDeparture() {
        carriedParcel(true);
        java.time.ZoneId zone = com.yadony.api.matching.TripTimezones.zoneOf(announcement.getTimezone());
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now(zone);

        announcement.setDepartureDate(now.plusMinutes(20).toLocalDate());
        announcement.setDepartureTime(now.plusMinutes(20).toLocalTime());
        assertThat(service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID).trackingActive()).isTrue();

        announcement.setDepartureDate(now.plusMinutes(90).toLocalDate());
        announcement.setDepartureTime(now.plusMinutes(90).toLocalTime());
        assertThat(service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID).trackingActive()).isFalse();
    }

    @Test
    @DisplayName("absent (null) pour un lecteur non propriétaire ou anonyme, sans même lire les colis")
    void trackingActive_nullForNonOwnerAndGuest() {
        announcement.setDepartureDate(LocalDate.now().minusDays(1));
        carriedParcel(true);

        assertThat(service.getAnnouncementDetail(ANNOUNCEMENT_ID, OTHER_UID).trackingActive()).isNull();
        assertThat(service.getAnnouncementDetail(ANNOUNCEMENT_ID, null).trackingActive()).isNull();
        verify(bidRepository, never()).existsByAnnouncementIdAndStatusIn(any(), any());
    }

    @Test
    @DisplayName("la copie withTrackingActive garde le reste du détail")
    void withTrackingActive_keepsTheRest() {
        AnnouncementDetailResponse base = service.getAnnouncementDetail(ANNOUNCEMENT_ID, TRAVELER_UID);

        AnnouncementDetailResponse copy = base.withTrackingActive(true);

        assertThat(copy.trackingActive()).isTrue();
        assertThat(copy.withTrackingActive(false)).isEqualTo(base);
    }
}
