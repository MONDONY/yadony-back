package com.yadony.api.alerts;

import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.events.AnnouncementCreatedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CorridorAlertTripMatchListenerTest {

    @Mock AnnouncementRepository announcementRepository;
    @Mock AlertService alertService;
    @Mock CorridorAlertRepository alertRepository;
    @Mock NotificationDispatcher notificationDispatcher;
    @InjectMocks CorridorAlertTripMatchListener listener;

    final UUID tripId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();

    private static void setId(Object target, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(target, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private AnnouncementEntity trip() {
        AnnouncementEntity a = new AnnouncementEntity();
        setId(a, tripId);
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        return a;
    }

    private CorridorAlertEntity alert(LocalDateTime lastNotifiedAt) {
        CorridorAlertEntity a = new CorridorAlertEntity();
        setId(a, UUID.randomUUID());
        a.setOwnerId(UUID.randomUUID());
        a.setDirection(AlertDirection.SENDER_WANTS_TRIPS);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setActive(true);
        a.setLastNotifiedAt(lastNotifiedAt);
        return a;
    }

    private AnnouncementCreatedEvent event() {
        return new AnnouncementCreatedEvent(tripId, "Paris", "", "Bamako", "");
    }

    @Test
    void onCreated_match_notifiesAndStampsLastNotified() {
        AnnouncementEntity trip = trip();
        CorridorAlertEntity alert = alert(null);
        when(announcementRepository.findById(tripId)).thenReturn(Optional.of(trip));
        when(alertService.findSenderAlertsMatchingTrip(trip)).thenReturn(List.of(alert));
        when(notificationDispatcher.notifyUnlessBlocked(
                eq(alert.getOwnerId()), eq(travelerId), any(), any(), anyMap())).thenReturn(true);

        listener.onAnnouncementCreated(event());

        verify(notificationDispatcher).notifyUnlessBlocked(
                eq(alert.getOwnerId()), eq(travelerId), contains("trajet pour votre alerte"), any(),
                argThat(d -> tripId.toString().equals(d.get("announcementId"))));
        assertThat(alert.getLastNotifiedAt()).isNotNull();
        verify(alertRepository).save(alert);
    }

    /**
     * Confidentialité — le voyageur et le propriétaire de l'alerte sont masqués l'un pour
     * l'autre : le dispatcher supprime la notification, et l'alerte ne doit pas être
     * horodatée, sinon le digest sauterait les trajets visibles de la même fenêtre.
     */
    @Test
    void onCreated_ownerBlockedWithTraveler_noStampNoSave() {
        AnnouncementEntity trip = trip();
        CorridorAlertEntity alert = alert(null);
        when(announcementRepository.findById(tripId)).thenReturn(Optional.of(trip));
        when(alertService.findSenderAlertsMatchingTrip(trip)).thenReturn(List.of(alert));
        when(notificationDispatcher.notifyUnlessBlocked(
                eq(alert.getOwnerId()), eq(travelerId), any(), any(), anyMap())).thenReturn(false);

        listener.onAnnouncementCreated(event());

        assertThat(alert.getLastNotifiedAt()).isNull();
        verify(alertRepository, never()).save(any());
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), anyMap());
    }

    /** Fréquence quotidienne ou silencieuse : rien ne part en temps réel. */
    @Test
    void onCreated_nonInstantAlert_doesNotNotify() {
        AnnouncementEntity trip = trip();
        CorridorAlertEntity daily = alert(null);
        daily.setNotifyMode(AlertNotifyMode.DAILY);
        CorridorAlertEntity muted = alert(null);
        muted.setNotifyMode(AlertNotifyMode.MUTED);
        when(announcementRepository.findById(tripId)).thenReturn(Optional.of(trip));
        when(alertService.findSenderAlertsMatchingTrip(trip)).thenReturn(List.of(daily, muted));

        listener.onAnnouncementCreated(event());

        verifyNoInteractions(notificationDispatcher);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void onCreated_withinCooldown_doesNotNotify() {
        AnnouncementEntity trip = trip();
        // notifiée à l'instant → dans le cooldown
        CorridorAlertEntity alert = alert(LocalDateTime.now(ZoneOffset.UTC));
        when(announcementRepository.findById(tripId)).thenReturn(Optional.of(trip));
        when(alertService.findSenderAlertsMatchingTrip(trip)).thenReturn(List.of(alert));

        listener.onAnnouncementCreated(event());

        verifyNoInteractions(notificationDispatcher);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void onCreated_noMatch_doesNotNotify() {
        AnnouncementEntity trip = trip();
        when(announcementRepository.findById(tripId)).thenReturn(Optional.of(trip));
        when(alertService.findSenderAlertsMatchingTrip(trip)).thenReturn(List.of());

        listener.onAnnouncementCreated(event());

        verifyNoInteractions(notificationDispatcher);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void onCreated_tripNotFound_noop() {
        when(announcementRepository.findById(tripId)).thenReturn(Optional.empty());

        listener.onAnnouncementCreated(event());

        verifyNoInteractions(alertService, notificationDispatcher, alertRepository);
    }
}
