package com.yadony.api.matching.reception;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.events.ArrivalInstructionsUpdatedEvent;
import com.yadony.api.matching.events.BidRecipientChangedEvent;
import com.yadony.api.matching.events.RecipientReplacementRequestedEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.TripArrivedEvent;
import com.yadony.api.matching.events.TripRescheduledEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.tracking.events.ParcelDepartedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceptionNotificationListenerTest {

    @Mock BidRecipientLinkRepository linkRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock NotificationDispatcher notificationDispatcher;
    @InjectMocks ReceptionNotificationListener listener;

    private final UUID bidId = UUID.randomUUID();
    private final UUID recipientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    private BidRecipientLinkEntity link(ReceptionLinkStatus status) {
        BidRecipientLinkEntity l = new BidRecipientLinkEntity(bidId, recipientId);
        if (status != ReceptionLinkStatus.PENDING) {
            l.respond(status, null);
        }
        return l;
    }

    // ── Changement de destinataire (lot 3A) ─────────────────────────────────

    @Test
    void recipientChanged_previousRecipientAndTravelerAreNotified() {
        UUID travelerId = UUID.randomUUID();

        listener.onBidRecipientChanged(new BidRecipientChangedEvent(bidId, recipientId, travelerId));

        verify(notificationDispatcher).notifyUser(eq(recipientId), eq("Colis réattribué"),
                eq("L'expéditeur a changé de destinataire : ce colis n'est plus pour vous."),
                eq(Map.of("type", "RECIPIENT_PARCEL_REASSIGNED", "bidId", bidId.toString())));
        verify(notificationDispatcher).notifyUser(eq(travelerId), eq("Destinataire modifié"),
                eq("L'expéditeur a changé le destinataire d'un colis. Voyez le détail."),
                eq(Map.of("type", "RECIPIENT_CHANGED", "bidId", bidId.toString())));
    }

    @Test
    void recipientChanged_withoutPreviousRecipient_onlyTravelerIsNotified() {
        UUID travelerId = UUID.randomUUID();

        listener.onBidRecipientChanged(new BidRecipientChangedEvent(bidId, null, travelerId));

        verify(notificationDispatcher).notifyUser(eq(travelerId), anyString(), anyString(),
                eq(Map.of("type", "RECIPIENT_CHANGED", "bidId", bidId.toString())));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(),
                eq(Map.of("type", "RECIPIENT_PARCEL_REASSIGNED", "bidId", bidId.toString())));
    }

    @Test
    void recipientChanged_failureForOne_stillNotifiesTheOther() {
        UUID travelerId = UUID.randomUUID();
        when(notificationDispatcher.messagesFor(recipientId)).thenThrow(new IllegalStateException("boom"));

        assertThatCode(() -> listener.onBidRecipientChanged(
                new BidRecipientChangedEvent(bidId, recipientId, travelerId))).doesNotThrowAnyException();

        verify(notificationDispatcher).notifyUser(eq(travelerId), anyString(), anyString(), any());
    }

    @Test
    void replacementRequested_notifiesTheSender() {
        UUID senderId = UUID.randomUUID();

        listener.onRecipientReplacementRequested(new RecipientReplacementRequestedEvent(bidId, senderId));

        verify(notificationDispatcher).notifyUser(eq(senderId), eq("Destinataire à remplacer"),
                eq("Destinataire refusé. Le voyageur vous demande d'en désigner un autre."),
                eq(Map.of("type", "RECIPIENT_REPLACEMENT_REQUESTED", "bidId", bidId.toString())));
    }

    @Test
    void replacementRequested_nullBid_noop() {
        listener.onRecipientReplacementRequested(new RecipientReplacementRequestedEvent(null, UUID.randomUUID()));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void replacementRequested_dispatchFailure_isSwallowed() {
        UUID senderId = UUID.randomUUID();
        when(notificationDispatcher.messagesFor(senderId)).thenThrow(new IllegalStateException("boom"));
        assertThatCode(() -> listener.onRecipientReplacementRequested(
                new RecipientReplacementRequestedEvent(bidId, senderId))).doesNotThrowAnyException();
    }

    @Test
    void recipientChanged_nullBid_noop() {
        listener.onBidRecipientChanged(new BidRecipientChangedEvent(null, recipientId, UUID.randomUUID()));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void departed_confirmedRecipient_getsPickupCodePush() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.CONFIRMED)));

        listener.onParcelDeparted(new ParcelDepartedEvent(bidId));

        verify(notificationDispatcher).notifyUser(eq(recipientId), eq("Votre colis est en route"),
                eq("Votre code de retrait est disponible dans l'app."),
                eq(Map.of("type", "RECIPIENT_PARCEL_DEPARTED", "bidId", bidId.toString())));
    }

    @Test
    void departed_pendingRecipient_getsNothing() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.PENDING)));
        listener.onParcelDeparted(new ParcelDepartedEvent(bidId));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void departed_noLink_getsNothing() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        listener.onParcelDeparted(new ParcelDepartedEvent(bidId));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void tripArrived_notifiesConfirmedRecipientsWithArrivalCity() {
        UUID annId = UUID.randomUUID();
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", annId);
        a.setArrivalCity("Dakar");
        when(announcementRepository.findById(annId)).thenReturn(Optional.of(a));
        UUID otherBid = UUID.randomUUID();
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.CONFIRMED)));
        when(linkRepository.findByBidId(otherBid)).thenReturn(Optional.empty());

        listener.onTripArrived(new TripArrivedEvent(annId, List.of(
                new TripArrivedEvent.BidTarget(bidId, UUID.randomUUID()),
                new TripArrivedEvent.BidTarget(otherBid, UUID.randomUUID()))));

        verify(notificationDispatcher).notifyUser(eq(recipientId), eq("Votre colis est arrivé"),
                eq("Il est à Dakar. Préparez votre code de retrait."),
                eq(Map.of("type", "RECIPIENT_PARCEL_ARRIVED", "bidId", bidId.toString())));
    }

    @Test
    void tripArrived_unknownAnnouncement_usesGenericBody() {
        UUID annId = UUID.randomUUID();
        when(announcementRepository.findById(annId)).thenReturn(Optional.empty());
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.CONFIRMED)));

        listener.onTripArrived(new TripArrivedEvent(annId, List.of(new TripArrivedEvent.BidTarget(bidId, UUID.randomUUID()))));

        verify(notificationDispatcher).notifyUser(eq(recipientId), eq("Votre colis est arrivé"),
                eq("Préparez votre code de retrait pour la remise."), any());
    }

    @Test
    void deliveryConfirmed_notifiesConfirmedRecipient() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.CONFIRMED)));

        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));

        verify(notificationDispatcher).notifyUser(eq(recipientId), eq("Colis remis"),
                eq("La remise de votre colis est confirmée."),
                eq(Map.of("type", "RECIPIENT_PARCEL_DELIVERED", "bidId", bidId.toString())));
    }

    @Test
    void deliveryConfirmed_declinedRecipient_getsNothing() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.DECLINED)));
        listener.onDeliveryConfirmed(new DeliveryConfirmedEvent(bidId, UUID.randomUUID(), UUID.randomUUID()));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void failure_isSwallowed() {
        when(linkRepository.findByBidId(bidId)).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> listener.onParcelDeparted(new ParcelDepartedEvent(bidId))).doesNotThrowAnyException();
    }

    // ── Annulation ──────────────────────────────────────────────────────────

    private TripCancelledEvent tripCancelled(UUID... bidIds) {
        return new TripCancelledEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID()),
                "TRAVELER_CANCELLED", List.of(bidIds));
    }

    private void verifyCancelledPush() {
        verify(notificationDispatcher).notifyUser(eq(recipientId), eq("Envoi annulé"),
                eq("Le transport prévu pour votre colis a été annulé."),
                eq(Map.of("type", "RECIPIENT_PARCEL_CANCELLED", "bidId", bidId.toString())));
    }

    @Test
    void tripCancelled_confirmedRecipient_isNotified() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.CONFIRMED)));
        listener.onTripCancelled(tripCancelled(bidId));
        verifyCancelledPush();
    }

    @Test
    void tripCancelled_pendingRecipient_isNotifiedToo() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.PENDING)));
        listener.onTripCancelled(tripCancelled(bidId));
        verifyCancelledPush();
    }

    @Test
    void tripCancelled_declinedOrNoLink_getsNothing() {
        UUID otherBid = UUID.randomUUID();
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.DECLINED)));
        when(linkRepository.findByBidId(otherBid)).thenReturn(Optional.empty());
        listener.onTripCancelled(tripCancelled(bidId, otherBid));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void tripCancelled_nullBidList_isIgnored() {
        listener.onTripCancelled(new TripCancelledEvent(UUID.randomUUID(), UUID.randomUUID(), List.of(),
                "X", null));
        verify(linkRepository, never()).findByBidId(any());
    }

    @Test
    void tripCancelled_failureOnOneBid_doesNotStopTheOthers() {
        UUID otherBid = UUID.randomUUID();
        when(linkRepository.findByBidId(otherBid)).thenThrow(new IllegalStateException("db down"));
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.CONFIRMED)));
        assertThatCode(() -> listener.onTripCancelled(tripCancelled(otherBid, bidId))).doesNotThrowAnyException();
        verifyCancelledPush();
    }

    @Test
    void bidRejected_linkedParcel_notifiesRecipient() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.PENDING)));
        listener.onBidRejected(new BidRejectedEvent(bidId, UUID.randomUUID(), "CANCELLED_BY_SENDER"));
        verifyCancelledPush();
    }

    @Test
    void bidRejected_noLink_getsNothing() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.empty());
        listener.onBidRejected(new BidRejectedEvent(bidId, UUID.randomUUID(), "TRAVELER_NO_RESPONSE"));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void senderNoShowConfirmed_notifiesConfirmedRecipient() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.CONFIRMED)));
        listener.onCancellationConfirmed(new CancellationConfirmedEvent(bidId, UUID.randomUUID(),
                CancellationReason.SENDER_NO_SHOW));
        verifyCancelledPush();
    }

    @Test
    void senderNoShowConfirmed_declinedRecipient_getsNothing() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.DECLINED)));
        listener.onCancellationConfirmed(new CancellationConfirmedEvent(bidId, UUID.randomUUID(),
                CancellationReason.SENDER_NO_SHOW));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    // ── Report ──────────────────────────────────────────────────────────────

    private TripRescheduledEvent rescheduled(UUID... bidIds) {
        List<TripRescheduledEvent.Target> targets = java.util.Arrays.stream(bidIds)
                .map(id -> new TripRescheduledEvent.Target(id, UUID.randomUUID(), true))
                .toList();
        return new TripRescheduledEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "WEATHER",
                LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 14), null, targets);
    }

    @Test
    void tripRescheduled_confirmedRecipient_isNotified() {
        UUID otherBid = UUID.randomUUID();
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.CONFIRMED)));
        when(linkRepository.findByBidId(otherBid)).thenReturn(Optional.empty());

        listener.onTripRescheduled(rescheduled(bidId, otherBid));

        verify(notificationDispatcher).notifyUser(eq(recipientId), eq("Nouvelles dates"),
                eq("Le trajet de votre colis a changé de date. Voyez le détail."),
                eq(Map.of("type", "RECIPIENT_PARCEL_RESCHEDULED", "bidId", bidId.toString())));
    }

    @Test
    void tripRescheduled_pendingRecipient_getsNothing() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.PENDING)));
        listener.onTripRescheduled(rescheduled(bidId));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void tripRescheduled_declinedRecipient_getsNothing() {
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.DECLINED)));
        listener.onTripRescheduled(rescheduled(bidId));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void tripRescheduled_failure_isSwallowed() {
        when(linkRepository.findByBidId(bidId)).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> listener.onTripRescheduled(rescheduled(bidId))).doesNotThrowAnyException();
    }

    // ── Lot 3B : arrivée et instructions de retrait ─────────────────────────

    @Test
    void tripArrived_pendingRecipient_isNotifiedToConfirm() {
        UUID annId = UUID.randomUUID();
        when(announcementRepository.findById(annId)).thenReturn(Optional.empty());
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.PENDING)));

        listener.onTripArrived(new TripArrivedEvent(annId, List.of(new TripArrivedEvent.BidTarget(bidId, UUID.randomUUID()))));

        verify(notificationDispatcher).notifyUser(eq(recipientId), eq("Votre colis est arrivé"), anyString(),
                eq(Map.of("type", "RECIPIENT_PARCEL_ARRIVED", "bidId", bidId.toString())));
    }

    @Test
    void tripArrived_declinedRecipient_getsNothing() {
        UUID annId = UUID.randomUUID();
        when(announcementRepository.findById(annId)).thenReturn(Optional.empty());
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.DECLINED)));

        listener.onTripArrived(new TripArrivedEvent(annId, List.of(new TripArrivedEvent.BidTarget(bidId, UUID.randomUUID()))));

        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void arrivalInstructionsUpdated_confirmedRecipient_isNotified() {
        UUID otherBid = UUID.randomUUID();
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.CONFIRMED)));
        when(linkRepository.findByBidId(otherBid)).thenReturn(Optional.empty());

        listener.onArrivalInstructionsUpdated(
                new ArrivalInstructionsUpdatedEvent(UUID.randomUUID(), List.of(bidId, otherBid)));

        verify(notificationDispatcher).notifyUser(eq(recipientId), eq("Retrait mis à jour"),
                eq("Le voyageur a modifié les instructions de retrait de votre colis."),
                eq(Map.of("type", "RECIPIENT_PICKUP_UPDATED", "bidId", bidId.toString())));
    }

    @Test
    void arrivalInstructionsUpdated_pendingOrDeclinedRecipient_getsNothing() {
        UUID declinedBid = UUID.randomUUID();
        when(linkRepository.findByBidId(bidId)).thenReturn(Optional.of(link(ReceptionLinkStatus.PENDING)));
        BidRecipientLinkEntity declined = new BidRecipientLinkEntity(declinedBid, recipientId);
        declined.respond(ReceptionLinkStatus.DECLINED, null);
        when(linkRepository.findByBidId(declinedBid)).thenReturn(Optional.of(declined));

        listener.onArrivalInstructionsUpdated(
                new ArrivalInstructionsUpdatedEvent(UUID.randomUUID(), List.of(bidId, declinedBid)));

        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }

    @Test
    void arrivalInstructionsUpdated_nullBidIds_isIgnored() {
        listener.onArrivalInstructionsUpdated(new ArrivalInstructionsUpdatedEvent(UUID.randomUUID(), null));
        verify(notificationDispatcher, never()).notifyUser(any(), anyString(), anyString(), any());
    }
}
