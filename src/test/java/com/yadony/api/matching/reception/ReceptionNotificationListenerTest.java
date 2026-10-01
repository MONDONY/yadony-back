package com.yadony.api.matching.reception;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.events.TripArrivedEvent;
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
}
