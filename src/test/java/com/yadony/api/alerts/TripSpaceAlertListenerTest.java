package com.yadony.api.alerts;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.events.TripCapacityFreedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TripSpaceAlertListener — notification au passage FULL → ACTIVE (FLUTTER-JH)")
class TripSpaceAlertListenerTest {

    private static final Instant NOW = Instant.parse("2026-10-11T10:00:00Z");

    @Mock TripSpaceAlertRepository repository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock NotificationDispatcher dispatcher;

    private TripSpaceAlertListener listener;
    private AnnouncementEntity trip;
    private final UUID tripId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new TripSpaceAlertListener(repository, announcementRepository, dispatcher);
        listener.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        trip = new AnnouncementEntity();
        setId(trip, tripId);
        trip.setTravelerId(travelerId);
        trip.setDepartureCity("Paris");
        trip.setArrivalCity("Dakar");
        trip.setDepartureDate(LocalDate.of(2026, 10, 20));
        trip.setStatus(AnnouncementStatus.ACTIVE);
        lenient().when(announcementRepository.findById(tripId)).thenReturn(Optional.of(trip));
        lenient().when(dispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    @Test
    @DisplayName("chaque abonné en attente est notifié une fois (push + centre de notifications), puis consommé")
    void notifiesEachPendingSubscriberOnce() {
        TripSpaceAlertEntity a = alert();
        TripSpaceAlertEntity b = alert();
        when(repository.findAllByAnnouncementIdAndNotifiedAtIsNull(tripId)).thenReturn(List.of(a, b));
        when(repository.consume(any(), any())).thenReturn(1);

        listener.onTripCapacityFreed(new TripCapacityFreedEvent(tripId, travelerId));

        verify(repository).consume(eq(a.getId()), eq(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)));
        verify(repository).consume(eq(b.getId()), any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
        verify(dispatcher).notifyUnlessBlocked(eq(a.getSubscriberId()), eq(travelerId),
                eq("De la place s'est libérée"), anyString(), data.capture());
        verify(dispatcher).notifyUnlessBlocked(eq(b.getSubscriberId()), eq(travelerId),
                anyString(), anyString(), anyMap());
        assertThat(data.getValue())
                .containsEntry("type", "TRIP_SPACE_FREED")
                .containsEntry("announcementId", tripId.toString());
    }

    @Test
    @DisplayName("abonnement déjà consommé par une libération concurrente : aucune deuxième notification")
    void alreadyConsumed_noSecondNotification() {
        when(repository.findAllByAnnouncementIdAndNotifiedAtIsNull(tripId)).thenReturn(List.of(alert()));
        when(repository.consume(any(), any())).thenReturn(0);

        listener.onTripCapacityFreed(new TripCapacityFreedEvent(tripId, travelerId));

        verify(dispatcher, never()).notifyUnlessBlocked(any(), any(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("trajet de nouveau complet, annulé ou parti : rien ne part, les abonnements attendent")
    void tripNoLongerBookable_nothingSent() {
        for (Runnable change : List.<Runnable>of(
                () -> trip.setStatus(AnnouncementStatus.FULL),
                () -> trip.setStatus(AnnouncementStatus.CANCELLED),
                () -> { trip.setStatus(AnnouncementStatus.ACTIVE); trip.setDepartureDate(LocalDate.of(2026, 10, 10)); },
                () -> { trip.setDepartureDate(LocalDate.of(2026, 10, 20));
                        trip.setHandoverDeadline(LocalDateTime.of(2026, 10, 11, 9, 0)); })) {
            change.run();
            listener.onTripCapacityFreed(new TripCapacityFreedEvent(tripId, travelerId));
        }
        verify(repository, never()).findAllByAnnouncementIdAndNotifiedAtIsNull(any());
        verify(repository, never()).consume(any(), any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("trajet introuvable ou sans abonné : rien")
    void unknownTripOrNoSubscriber_nothing() {
        UUID unknown = UUID.randomUUID();
        when(announcementRepository.findById(unknown)).thenReturn(Optional.empty());
        listener.onTripCapacityFreed(new TripCapacityFreedEvent(unknown, travelerId));

        when(repository.findAllByAnnouncementIdAndNotifiedAtIsNull(tripId)).thenReturn(List.of());
        listener.onTripCapacityFreed(new TripCapacityFreedEvent(tripId, travelerId));

        verify(repository, never()).consume(any(), any());
        verifyNoInteractions(dispatcher);
    }

    @Test
    @DisplayName("l'échec d'une notification n'empêche pas les suivantes")
    void oneFailure_doesNotStopOthers() {
        TripSpaceAlertEntity a = alert();
        TripSpaceAlertEntity b = alert();
        when(repository.findAllByAnnouncementIdAndNotifiedAtIsNull(tripId)).thenReturn(List.of(a, b));
        when(repository.consume(any(), any())).thenReturn(1);
        when(dispatcher.notifyUnlessBlocked(eq(a.getSubscriberId()), any(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("fcm"));

        listener.onTripCapacityFreed(new TripCapacityFreedEvent(tripId, travelerId));

        verify(dispatcher, times(1)).notifyUnlessBlocked(eq(b.getSubscriberId()), any(), anyString(), anyString(), anyMap());
    }

    private TripSpaceAlertEntity alert() {
        TripSpaceAlertEntity alert = new TripSpaceAlertEntity();
        setId(alert, UUID.randomUUID());
        alert.setAnnouncementId(tripId);
        alert.setSubscriberId(UUID.randomUUID());
        return alert;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
