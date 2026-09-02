package com.yadony.api.alerts;

import com.yadony.api.alerts.dto.AlertTripMatchDto;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.common.MatchingTextUtil;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
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
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceTripMatchesTest {

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
    }

    private static void setId(Object target, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private CorridorAlertEntity senderAlert() {
        CorridorAlertEntity a = new CorridorAlertEntity();
        setId(a, alertId);
        a.setOwnerId(ownerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDateFrom(LocalDate.of(2026, 7, 1));
        a.setDateTo(LocalDate.of(2026, 7, 31));
        a.setContentCategories(List.of());
        a.setActive(true);
        a.setDirection(AlertDirection.SENDER_WANTS_TRIPS);
        return a;
    }

    private AnnouncementEntity trip(UUID travelerId, LocalDate date, BigDecimal availableKg, BigDecimal pricePerKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, UUID.randomUUID());
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(date);
        a.setAvailableKg(availableKg);
        a.setPricePerKg(pricePerKg);
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Addr");
        a.setPickupLat(BigDecimal.ONE);
        a.setPickupLng(BigDecimal.ONE);
        a.setDeliveryAddressLabel("Dest");
        a.setDeliveryLat(BigDecimal.ONE);
        a.setDeliveryLng(BigDecimal.ONE);
        a.setTotalKg(availableKg);
        return a;
    }

    private UserEntity traveler(UUID id, String firstName, String lastName, BigDecimal rating) {
        UserEntity u = new UserEntity();
        setId(u, id);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setAverageRating(rating);
        return u;
    }

    @Test
    void getTripMatches_senderAlert_returnsOnlyInWindowTrip() {
        UUID travelerId = UUID.randomUUID();
        AnnouncementEntity inWindow = trip(travelerId, LocalDate.of(2026, 7, 10),
                new BigDecimal("15.00"), new BigDecimal("8.50"));
        AnnouncementEntity outOfWindow = trip(UUID.randomUUID(), LocalDate.of(2026, 8, 15),
                new BigDecimal("10.00"), new BigDecimal("7.00"));

        UserEntity travelerEntity = traveler(travelerId, "Moussa", "Diallo", new BigDecimal("4.7"));

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(senderAlert()));
        when(announcementRepository.findActiveByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(inWindow, outOfWindow));
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(travelerEntity));

        List<AlertTripMatchDto> matches = service.getTripMatches(uid, alertId);

        assertThat(matches).hasSize(1);
        AlertTripMatchDto dto = matches.get(0);
        assertThat(dto.departureDate()).isEqualTo(LocalDate.of(2026, 7, 10));
        // Valeur littérale, et non MatchingTextUtil.buildName(travelerEntity) : rederiver
        // l'attendu depuis le helper testé rendait l'assertion toujours vraie. Ce DTO part
        // vers un expéditeur, le patronyme y est donc abrégé.
        assertThat(dto.travelerName()).isEqualTo("Moussa D.");
        assertThat(dto.travelerInitials()).isEqualTo(MatchingTextUtil.buildInitials(travelerEntity));
        assertThat(dto.availableKg()).isEqualTo(new BigDecimal("15.00"));
        assertThat(dto.pricePerKg()).isEqualTo(new BigDecimal("8.50"));
        assertThat(dto.transportMode()).isEqualTo(TransportMode.PLANE);
        assertThat(dto.travelerRating()).isCloseTo(4.7, within(0.0001));
    }

    /** Un voyageur bloqué (dans un sens ou l'autre) ne doit pas remonter dans les
     *  correspondances d'une alerte : c'est la même liste de trajets que la recherche. */
    @Test
    void getTripMatches_omitsBlockedTravelers() {
        UUID blockedTraveler = UUID.randomUUID();
        UUID visibleTraveler = UUID.randomUUID();
        AnnouncementEntity fromBlocked = trip(blockedTraveler, LocalDate.of(2026, 7, 10),
                new BigDecimal("15.00"), new BigDecimal("8.50"));
        AnnouncementEntity fromVisible = trip(visibleTraveler, LocalDate.of(2026, 7, 12),
                new BigDecimal("10.00"), new BigDecimal("7.00"));

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(senderAlert()));
        when(announcementRepository.findActiveByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(fromBlocked, fromVisible));
        when(blockVisibility.hiddenUserIdsFor(ownerId)).thenReturn(java.util.Set.of(blockedTraveler));
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(traveler(visibleTraveler, "Awa", "Keita", null)));

        List<AlertTripMatchDto> matches = service.getTripMatches(uid, alertId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).announcementId()).isEqualTo(fromVisible.getId());
    }

    @Test
    void getTripMatches_ratingNull_defaultsToZero() {
        UUID travelerId = UUID.randomUUID();
        AnnouncementEntity inWindow = trip(travelerId, LocalDate.of(2026, 7, 15),
                new BigDecimal("5.00"), new BigDecimal("10.00"));
        UserEntity travelerEntity = traveler(travelerId, "Awa", "Keita", null);

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(senderAlert()));
        when(announcementRepository.findActiveByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(inWindow));
        when(userRepository.findAllById(anyCollection()))
                .thenReturn(List.of(travelerEntity));

        List<AlertTripMatchDto> matches = service.getTripMatches(uid, alertId);

        assertThat(matches).hasSize(1);
        assertThat(matches.get(0).travelerRating()).isEqualTo(0.0);
    }

    @Test
    void getTripMatches_notOwner_throwsNotFound() {
        CorridorAlertEntity foreign = senderAlert();
        foreign.setOwnerId(UUID.randomUUID());
        when(alertRepository.findById(alertId)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getTripMatches(uid, alertId))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void countMatches_travelerDirection_doesNotTouchAnnouncementRepository() {
        CorridorAlertEntity travelerAlert = new CorridorAlertEntity();
        setId(travelerAlert, alertId);
        travelerAlert.setOwnerId(ownerId);
        travelerAlert.setDepartureCity("Paris");
        travelerAlert.setArrivalCity("Bamako");
        travelerAlert.setContentCategories(List.of());
        travelerAlert.setActive(true);
        travelerAlert.setDirection(AlertDirection.TRAVELER_WANTS_PACKAGES);

        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(travelerAlert));
        when(packageRequestRepository.findOpenByCorridor("Paris", "Bamako")).thenReturn(List.of());

        List<com.yadony.api.alerts.dto.CorridorAlertResponse> result = service.list(uid);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).matchCount()).isEqualTo(0L);
        verifyNoInteractions(announcementRepository);
    }

    @Test
    void countMatches_senderDirection_usesAnnouncementRepository() {
        UUID travelerId = UUID.randomUUID();
        AnnouncementEntity inWindow = trip(travelerId, LocalDate.of(2026, 7, 10),
                new BigDecimal("15.00"), new BigDecimal("8.50"));

        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(senderAlert()));
        when(announcementRepository.findActiveByCorridor("Paris", "Bamako"))
                .thenReturn(List.of(inWindow));

        List<com.yadony.api.alerts.dto.CorridorAlertResponse> list = service.list(uid);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).matchCount()).isEqualTo(1L);
        verify(announcementRepository).findActiveByCorridor("Paris", "Bamako");
        verifyNoInteractions(packageRequestRepository);
    }

    private CorridorAlertEntity senderZoneAlert() {
        CorridorAlertEntity a = senderAlert();
        a.setCenterLat(new BigDecimal("48.856600"));
        a.setCenterLng(new BigDecimal("2.352200"));
        a.setRadiusKm(20);
        a.setCenterLabel("Châtelet, Paris");
        return a;
    }

    @Test
    void getTripMatches_zoneAlert_usesPickupRadiusQuery() {
        UUID travelerId = UUID.randomUUID();
        AnnouncementEntity inZone = trip(travelerId, LocalDate.of(2026, 7, 10),
                new BigDecimal("15.00"), new BigDecimal("8.50"));
        UserEntity travelerEntity = traveler(travelerId, "Moussa", "Diallo", new BigDecimal("4.7"));

        when(alertRepository.findById(alertId)).thenReturn(Optional.of(senderZoneAlert()));
        when(announcementRepository.findActiveByCorridorWithinPickupRadius(
                "Paris", "Bamako", 48.8566, 2.3522, 20))
                .thenReturn(List.of(inZone));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of(travelerEntity));

        List<AlertTripMatchDto> matches = service.getTripMatches(uid, alertId);

        assertThat(matches).hasSize(1);
        verify(announcementRepository).findActiveByCorridorWithinPickupRadius(
                "Paris", "Bamako", 48.8566, 2.3522, 20);
        verify(announcementRepository, never()).findActiveByCorridor(anyString(), anyString());
    }

    @Test
    void countMatches_senderZone_usesPickupRadiusQueryAndMapsZone() {
        AnnouncementEntity inZone = trip(UUID.randomUUID(), LocalDate.of(2026, 7, 10),
                new BigDecimal("15.00"), new BigDecimal("8.50"));

        when(alertRepository.findAllByOwnerId(ownerId)).thenReturn(List.of(senderZoneAlert()));
        when(announcementRepository.findActiveByCorridorWithinPickupRadius(
                "Paris", "Bamako", 48.8566, 2.3522, 20))
                .thenReturn(List.of(inZone));

        List<com.yadony.api.alerts.dto.CorridorAlertResponse> list = service.list(uid);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).matchCount()).isEqualTo(1L);
        assertThat(list.get(0).radiusKm()).isEqualTo(20);
        assertThat(list.get(0).centerLabel()).isEqualTo("Châtelet, Paris");
        assertThat(list.get(0).centerLat()).isEqualByComparingTo(new BigDecimal("48.856600"));
        verify(announcementRepository).findActiveByCorridorWithinPickupRadius(
                "Paris", "Bamako", 48.8566, 2.3522, 20);
        verify(announcementRepository, never()).findActiveByCorridor(anyString(), anyString());
    }

    // ── findSenderAlertsMatchingTrip (matching temps réel, inverse) ──────────

    private AnnouncementEntity tripAt(String dep, String arr, LocalDate date,
                                      BigDecimal pickupLat, BigDecimal pickupLng,
                                      AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, UUID.randomUUID());
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity(dep);
        a.setArrivalCity(arr);
        a.setDepartureDate(date);
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Addr");
        a.setPickupLat(pickupLat);
        a.setPickupLng(pickupLng);
        a.setDeliveryAddressLabel("Dest");
        a.setDeliveryLat(BigDecimal.ONE);
        a.setDeliveryLng(BigDecimal.ONE);
        a.setTotalKg(new BigDecimal("10.00"));
        a.setStatus(status);
        return a;
    }

    @Test
    void findSenderAlertsMatchingTrip_corridorAndDateMatch_returnsAlert() {
        when(alertRepository.findAllByActiveTrueAndDirection(AlertDirection.SENDER_WANTS_TRIPS))
                .thenReturn(List.of(senderAlert()));
        AnnouncementEntity trip = tripAt("Paris", "Bamako", LocalDate.of(2026, 7, 10),
                BigDecimal.ONE, BigDecimal.ONE, AnnouncementStatus.ACTIVE);

        assertThat(service.findSenderAlertsMatchingTrip(trip)).hasSize(1);
        verify(alertRepository).findAllByActiveTrueAndDirection(AlertDirection.SENDER_WANTS_TRIPS);
    }

    @Test
    void findSenderAlertsMatchingTrip_wrongCorridor_excluded() {
        when(alertRepository.findAllByActiveTrueAndDirection(AlertDirection.SENDER_WANTS_TRIPS))
                .thenReturn(List.of(senderAlert()));
        AnnouncementEntity trip = tripAt("Lyon", "Dakar", LocalDate.of(2026, 7, 10),
                BigDecimal.ONE, BigDecimal.ONE, AnnouncementStatus.ACTIVE);

        assertThat(service.findSenderAlertsMatchingTrip(trip)).isEmpty();
    }

    @Test
    void findSenderAlertsMatchingTrip_outOfDateWindow_excluded() {
        when(alertRepository.findAllByActiveTrueAndDirection(AlertDirection.SENDER_WANTS_TRIPS))
                .thenReturn(List.of(senderAlert()));
        AnnouncementEntity trip = tripAt("Paris", "Bamako", LocalDate.of(2026, 8, 15),
                BigDecimal.ONE, BigDecimal.ONE, AnnouncementStatus.ACTIVE);

        assertThat(service.findSenderAlertsMatchingTrip(trip)).isEmpty();
    }

    @Test
    void findSenderAlertsMatchingTrip_zoneInside_matches() {
        CorridorAlertEntity zoneAlert = senderAlert();
        zoneAlert.setCenterLat(BigDecimal.ONE);
        zoneAlert.setCenterLng(BigDecimal.ONE);
        zoneAlert.setRadiusKm(20);
        when(alertRepository.findAllByActiveTrueAndDirection(AlertDirection.SENDER_WANTS_TRIPS))
                .thenReturn(List.of(zoneAlert));
        // pickup (1,1) = centre → distance 0 → dans la zone
        AnnouncementEntity trip = tripAt("Paris", "Bamako", LocalDate.of(2026, 7, 10),
                BigDecimal.ONE, BigDecimal.ONE, AnnouncementStatus.ACTIVE);

        assertThat(service.findSenderAlertsMatchingTrip(trip)).hasSize(1);
    }

    @Test
    void findSenderAlertsMatchingTrip_zoneOutside_excluded() {
        CorridorAlertEntity zoneAlert = senderAlert();
        zoneAlert.setCenterLat(new BigDecimal("48.8566"));
        zoneAlert.setCenterLng(new BigDecimal("2.3522"));
        zoneAlert.setRadiusKm(20);
        when(alertRepository.findAllByActiveTrueAndDirection(AlertDirection.SENDER_WANTS_TRIPS))
                .thenReturn(List.of(zoneAlert));
        // pickup (1,1) très loin de Paris → hors zone 20 km
        AnnouncementEntity trip = tripAt("Paris", "Bamako", LocalDate.of(2026, 7, 10),
                BigDecimal.ONE, BigDecimal.ONE, AnnouncementStatus.ACTIVE);

        assertThat(service.findSenderAlertsMatchingTrip(trip)).isEmpty();
    }

    @Test
    void findSenderAlertsMatchingTrip_nonActiveTrip_emptyWithoutQuery() {
        AnnouncementEntity trip = tripAt("Paris", "Bamako", LocalDate.of(2026, 7, 10),
                BigDecimal.ONE, BigDecimal.ONE, AnnouncementStatus.CANCELLED);

        assertThat(service.findSenderAlertsMatchingTrip(trip)).isEmpty();
        verifyNoInteractions(alertRepository);
    }
}
