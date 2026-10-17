package com.yadony.api.notifications;

import com.yadony.api.matching.events.HandoverExpiredEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * « Remise non effectuée » (FLUTTER-QR) : un colis espèces dont la remise n'a été ni scannée
 * ni signalée à l'échéance est annulé sans fautif. Les deux parties sont prévenues avec un
 * texte neutre, chacune dans sa langue ; le voyageur apprend en plus que sa commission lui
 * est rendue quand elle avait été prélevée.
 *
 * <p>Après commit : le colis est déjà annulé quand l'app recharge en ouvrant la notification.
 * Type {@code BID_EXPIRED} (l'app ouvre déjà {@code /bids/{bidId}} pour ce type), motif
 * {@code HANDOVER_NOT_CONFIRMED} pour distinguer ce cas de la date limite de dépôt d'une
 * demande non engagée.
 */
@Component
public class HandoverExpiredNotificationListener {

    static final String TYPE = "BID_EXPIRED";
    static final String REASON = com.yadony.api.matching.NoShowService.HANDOVER_NOT_CONFIRMED_REASON;

    private final NotificationDispatcher dispatcher;

    public HandoverExpiredNotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onHandoverExpired(HandoverExpiredEvent e) {
        Map<String, String> data = data(e);
        if (e.senderId() != null) {
            var forSender = NotificationTexts.handoverExpired(dispatcher.messagesFor(e.senderId()), false);
            dispatcher.notifyUser(e.senderId(), forSender.title(), forSender.body(), data);
        }
        if (e.travelerId() != null) {
            var forTraveler = NotificationTexts.handoverExpired(
                    dispatcher.messagesFor(e.travelerId()), e.commissionCharged());
            dispatcher.notifyUser(e.travelerId(), forTraveler.title(), forTraveler.body(), data);
        }
    }

    static Map<String, String> data(HandoverExpiredEvent e) {
        Map<String, String> data = new HashMap<>();
        data.put("type", TYPE);
        data.put("reason", REASON);
        data.put("bidId", e.bidId().toString());
        if (e.announcementId() != null) {
            data.put("announcementId", e.announcementId().toString());
        }
        return Map.copyOf(data);
    }
}
