package com.yadony.api.notifications;

import com.yadony.api.matching.events.BidCardAuthorizationExpiredEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * « L'autorisation de votre carte a expiré » : prévient l'expéditeur dont la demande a été
 * éteinte parce que le voyageur n'a pas répondu avant la fin de l'autorisation carte.
 *
 * <p>Expéditeur seulement : le voyageur n'a rien perdu. Après commit : la demande est déjà
 * EXPIRED en base quand l'app recharge en ouvrant la notification, qui mène à la demande.
 * Voie système ({@link NotificationDispatcher#notifyUser}), sans règle de blocage : c'est l'issue
 * d'une demande payée.
 */
@Component
public class CardAuthorizationExpiredNotificationListener {

    static final String TYPE = "BID_EXPIRED";

    private final NotificationDispatcher dispatcher;

    public CardAuthorizationExpiredNotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onCardAuthorizationExpired(BidCardAuthorizationExpiredEvent e) {
        var text = NotificationTexts.cardAuthorizationExpired(dispatcher.messagesFor(e.senderId()));
        dispatcher.notifyUser(e.senderId(), text.title(), text.body(), data(e));
    }

    static Map<String, String> data(BidCardAuthorizationExpiredEvent e) {
        Map<String, String> data = new HashMap<>();
        data.put("type", TYPE);
        data.put("reason", BidCardAuthorizationExpiredEvent.REASON);
        data.put("bidId", e.bidId().toString());
        if (e.announcementId() != null) {
            data.put("announcementId", e.announcementId().toString());
        }
        return Map.copyOf(data);
    }
}
