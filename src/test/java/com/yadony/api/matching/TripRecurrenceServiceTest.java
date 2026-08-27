package com.yadony.api.matching;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.TripRecurrenceRequest;
import com.yadony.api.payments.cash.PaymentMethod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripRecurrenceServiceTest {

    @Mock TripRecurrenceRepository repository;
    @Mock AnnouncementService announcementService;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @InjectMocks TripRecurrenceService service;

    private final UUID userId = UUID.randomUUID();

    private TripRecurrenceEntity entity(String weekdays, int horizon, LocalDate lastGen) {
        TripRecurrenceEntity e = new TripRecurrenceEntity();
        e.setUserId(userId);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setTransportMode("PLANE");
        e.setCapacityUnit("SUITCASE_23KG");
        e.setAvailableKg(23.0);
        e.setPricePerKg(8.0);
        e.setAcceptedCategories("Vêtements,Documents");
        e.setPickupLabel("12 rue de la Paix");
        e.setPickupLat(48.86);
        e.setPickupLng(2.33);
        e.setDeliveryLabel("Aéroport CDG");
        e.setDeliveryLat(49.01);
        e.setDeliveryLng(2.55);
        e.setDepartureTime(LocalTime.of(14, 0));
        e.setWeekdays(weekdays);
        e.setHorizonDays(horizon);
        e.setStartDate(LocalDate.now());
        e.setActive(true);
        e.setLastGeneratedDate(lastGen);
        return e;
    }

    private void mockUser() {
        UserEntity user = mock(UserEntity.class);
        when(user.getFirebaseUid()).thenReturn("firebase-uid");
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void generate_createsForEveryMatchingDayWithinHorizon() {
        mockUser();
        // tous les jours cochés, horizon 6 → 7 jours (today..today+6) → 7 trajets
        TripRecurrenceEntity rec = entity("1111111", 6, null);

        int created = service.generateForRecurrence(rec);

        assertThat(created).isEqualTo(7);
        verify(announcementService, times(7)).createAnnouncement(eq("firebase-uid"), any());
        assertThat(rec.getLastGeneratedDate()).isEqualTo(LocalDate.now().plusDays(6));
    }

    @Test
    void generate_includesCashAndArrivalTimeFromRecurrence() {
        mockUser();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        rec.setCashAccepted(true);
        rec.setArrivalTime(LocalTime.of(18, 30));

        service.generateForRecurrence(rec);

        ArgumentCaptor<AnnouncementRequest> cap = ArgumentCaptor.forClass(AnnouncementRequest.class);
        verify(announcementService).createAnnouncement(eq("firebase-uid"), cap.capture());
        assertThat(cap.getValue().acceptedPaymentMethods())
                .contains(PaymentMethod.STRIPE, PaymentMethod.CASH);
        assertThat(cap.getValue().arrivalTime()).isEqualTo(LocalTime.of(18, 30));
    }

    @Test
    void generate_skipsNonMatchingWeekdays() {
        mockUser();
        // aucun jour coché → aucun trajet
        TripRecurrenceEntity rec = entity("0000000", 6, null);

        int created = service.generateForRecurrence(rec);

        assertThat(created).isZero();
        verify(announcementService, never()).createAnnouncement(anyString(), any());
    }

    @Test
    void generate_respectsLastGeneratedDate() {
        // déjà généré jusqu'à l'horizon (today) → rien de neuf
        TripRecurrenceEntity rec = entity("1111111", 0, LocalDate.now());

        int created = service.generateForRecurrence(rec);

        assertThat(created).isZero();
        verifyNoInteractions(announcementService);
    }

    @Test
    void generate_isolatesCreationFailures() {
        mockUser();
        when(announcementService.createAnnouncement(anyString(), any()))
                .thenThrow(new RuntimeException("limite PRO atteinte"));
        TripRecurrenceEntity rec = entity("1111111", 1, null); // 2 jours

        int created = service.generateForRecurrence(rec);

        assertThat(created).isZero(); // aucune réussie
        verify(announcementService, times(2)).createAnnouncement(anyString(), any()); // mais 2 tentées
        assertThat(rec.getLastGeneratedDate()).isEqualTo(LocalDate.now().plusDays(1));
    }

    @Test
    void generate_missingUser_skips() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        TripRecurrenceEntity rec = entity("1111111", 3, null);

        int created = service.generateForRecurrence(rec);

        assertThat(created).isZero();
        verifyNoInteractions(announcementService);
    }

    @Test
    void create_savesAndGeneratesWhenActive() {
        mockUser();
        var req = request("1111111", 0, true);

        service.create(userId, req);

        verify(repository, atLeastOnce()).save(any(TripRecurrenceEntity.class));
        verify(announcementService, times(1)).createAnnouncement(eq("firebase-uid"), any());
        verify(auditService).log(eq("TRIP_RECURRENCE"), any(), eq("TRIP_RECURRENCE_CREATED"), eq(userId), anyMap());
    }

    // C2 : normalisation à l'écriture — un client pas à jour envoie des libellés/codes
    // legacy, la récurrence doit être persistée avec les libellés canoniques.
    @Test
    void create_legacyAcceptedCategories_areNormalizedOnWrite() {
        var req = new TripRecurrenceRequest(
                null, "Paris", "Dakar", "PLANE", "SUITCASE_23KG",
                23.0, 8.0, List.of("Hi-fi", "Téléphone", "Vêtements"),
                new AddressDto("12 rue de la Paix", 48.86, 2.33),
                new AddressDto("Aéroport CDG", 49.01, 2.55),
                LocalTime.of(14, 0), LocalTime.of(18, 30), false, "1111111", 0, false);

        ArgumentCaptor<TripRecurrenceEntity> captor = ArgumentCaptor.forClass(TripRecurrenceEntity.class);
        var dto = service.create(userId, req);

        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getAcceptedCategories()).isEqualTo("Téléphone & électronique,Vêtements & tissus");
        assertThat(dto.acceptedCategories()).containsExactly("Téléphone & électronique", "Vêtements & tissus");
    }

    @Test
    void create_inactive_doesNotGenerate() {
        var req = request("1111111", 0, false);

        service.create(userId, req);

        verifyNoInteractions(announcementService);
    }

    @Test
    void generateDueTrips_iteratesActiveRecurrences() {
        mockUser();
        when(repository.findByActiveTrue()).thenReturn(List.of(entity("1111111", 0, null)));

        int total = service.generateDueTrips();

        assertThat(total).isEqualTo(1);
    }

    @Test
    void update_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(userId, id, request("1111111", 0, true)))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void delete_softDeletes() {
        UUID id = UUID.randomUUID();
        TripRecurrenceEntity rec = entity("1111111", 0, null);
        when(repository.findByUserIdAndId(userId, id)).thenReturn(Optional.of(rec));

        service.delete(userId, id);

        assertThat(rec.getDeletedAt()).isNotNull();
        verify(auditService).log(eq("TRIP_RECURRENCE"), any(), eq("TRIP_RECURRENCE_DELETED"), eq(userId), anyMap());
    }

    @Test
    void toDto_derivesLifecycleStatusAndNextOccurrence() {
        LocalDate today = LocalDate.of(2026, 9, 1);
        TripRecurrenceEntity upcoming = entity("0001000", 14, null);
        upcoming.setStartDate(LocalDate.of(2026, 9, 10));
        upcoming.setPublicationLeadDays(7);

        var upcomingDto = service.toDto(upcoming, today);

        assertThat(upcomingDto.status()).isEqualTo(TripRecurrenceStatus.UPCOMING);
        assertThat(upcomingDto.nextDepartureDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(upcomingDto.nextPublicationDate()).isEqualTo(LocalDate.of(2026, 9, 3));

        upcoming.setActive(false);
        assertThat(service.toDto(upcoming, today).status()).isEqualTo(TripRecurrenceStatus.PAUSED);

        upcoming.setEndDate(today.minusDays(1));
        upcoming.setLastPublicationErrorCode("kyc-required");
        assertThat(service.toDto(upcoming, today).status()).isEqualTo(TripRecurrenceStatus.TERMINATED);
    }

    private TripRecurrenceRequest request(String weekdays, int horizon, boolean active) {
        return new TripRecurrenceRequest(
                null, "Paris", "Dakar", "PLANE", "SUITCASE_23KG",
                23.0, 8.0, List.of("Vêtements", "Documents"),
                new AddressDto("12 rue de la Paix", 48.86, 2.33),
                new AddressDto("Aéroport CDG", 49.01, 2.55),
                LocalTime.of(14, 0), LocalTime.of(18, 30), false, weekdays, horizon, active);
    }
}
