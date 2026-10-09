package com.yadony.api.notifications;

import com.yadony.api.matching.events.BidHandoverDeadlinePassedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * « La date limite de dépôt est passée : demande annulée » (FLUTTER-GA), aux deux parties.
 *
 * <p>Une demande carte jamais payée est supprimée logiquement par l'expiration de paiement
 * existante : sa notification ouvre le trajet, pas la demande ({@link #data}).
 *
 * <p>Après commit : la demande est déjà annulée en base quand l'app recharge en ouvrant la
 * notification. Voie système ({@link NotificationDispatcher#notifyUser}), sans règle de
 * blocage : il s'agit de l'issue d'une demande et, le cas échéant, d'un remboursement.
 */
@Component
public class HandoverDeadlineNotificationListener {

    static final String TYPE = "BID_EXPIRED";
    static final String REASON = com.yadony.api.matching.HandoverDeadlineRules.EXPIRY_REASON;

    private final NotificationDispatcher dispatcher;

    public HandoverDeadlineNotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onHandoverDeadlinePassed(BidHandoverDeadlinePassedEvent e) {
        Map<String, String> data = data(e);

        var m = dispatcher.messagesFor(e.senderId());
        var forSender = e.refunded()
                ? NotificationTexts.handoverDeadlinePassedRefunded(m)
                : NotificationTexts.handoverDeadlinePassed(m);
        dispatcher.notifyUser(e.senderId(), forSender.title(), forSender.body(), data);

        if (e.notifyTraveler() && e.travelerId() != null) {
            var forTraveler = NotificationTexts.handoverDeadlinePassed(dispatcher.messagesFor(e.travelerId()));
            dispatcher.notifyUser(e.travelerId(), forTraveler.title(), forTraveler.body(), data);
        }
    }

    /**
     * Demande conservée (EXPIRED) : vers la demande. Demande carte jamais payée, supprimée
     * logiquement à l'abandon : sans {@code bidId}, l'app ouvre le trajet
     * ({@code announcementId}) au lieu d'une demande introuvable.
     */
    static Map<String, String> data(BidHandoverDeadlinePassedEvent e) {
        Map<String, String> data = new HashMap<>();
        data.put("type", TYPE);
        data.put("reason", REASON);
        if (!e.requestRemoved()) {
            data.put("bidId", e.bidId().toString());
        }
        if (e.announcementId() != null) {
            data.put("announcementId", e.announcementId().toString());
        }
        return Map.copyOf(data);
    }
}
