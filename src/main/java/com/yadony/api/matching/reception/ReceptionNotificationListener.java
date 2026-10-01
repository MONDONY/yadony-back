package com.yadony.api.matching.reception;

import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.events.TripArrivedEvent;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationText;
import com.yadony.api.notifications.NotificationTexts;
import com.yadony.api.tracking.events.DeliveryConfirmedEvent;
import com.yadony.api.tracking.events.ParcelDepartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Tient au courant le destinataire qui suit son colis dans l'app (lien CONFIRMED) :
 * départ (code de retrait disponible), arrivée, remise. Push FCM uniquement, jamais
 * de SMS. Un destinataire sans lien confirmé ne reçoit rien.
 */
@Component
public class ReceptionNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(ReceptionNotificationListener.class);

    private final BidRecipientLinkRepository linkRepository;
    private final AnnouncementRepository announcementRepository;
    private final NotificationDispatcher notificationDispatcher;

    public ReceptionNotificationListener(BidRecipientLinkRepository linkRepository,
                                         AnnouncementRepository announcementRepository,
                                         NotificationDispatcher notificationDispatcher) {
        this.linkRepository = linkRepository;
        this.announcementRepository = announcementRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onParcelDeparted(ParcelDepartedEvent event) {
        notifyConfirmedRecipient(event.bidId(), ReceptionNotifications.DEPARTED,
                m -> NotificationTexts.recipientParcelDeparted(m));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTripArrived(TripArrivedEvent event) {
        String arrivalCity = announcementRepository.findById(event.getAnnouncementId())
                .map(a -> a.getArrivalCity())
                .orElse(null);
        for (TripArrivedEvent.BidTarget target : event.getTargets()) {
            notifyConfirmedRecipient(target.bidId(), ReceptionNotifications.ARRIVED,
                    m -> NotificationTexts.recipientParcelArrived(m, arrivalCity));
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onDeliveryConfirmed(DeliveryConfirmedEvent event) {
        notifyConfirmedRecipient(event.getBidId(), ReceptionNotifications.DELIVERED,
                m -> NotificationTexts.recipientParcelDelivered(m));
    }

    private void notifyConfirmedRecipient(UUID bidId, String type,
                                          Function<com.yadony.api.common.i18n.Messages, NotificationText> text) {
        try {
            linkRepository.findByBidId(bidId)
                    .filter(l -> l.getStatus() == ReceptionLinkStatus.CONFIRMED)
                    .ifPresent(link -> {
                        UUID recipientId = link.getRecipientUserId();
                        NotificationText t = text.apply(notificationDispatcher.messagesFor(recipientId));
                        notificationDispatcher.notifyUser(recipientId, t.title(), t.body(),
                                Map.of("type", type, "bidId", bidId.toString()));
                    });
        } catch (Exception e) {
            log.warn("Notification {} au destinataire du colis {} impossible : {}", type, bidId, e.toString());
        }
    }
}
