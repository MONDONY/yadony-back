package com.yadony.api.notifications;

import com.yadony.api.support.SupportMessageAuthorType;
import com.yadony.api.support.events.SupportMessageCreatedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Push simple, sans repli SMS : une reponse du support n'a pas la criticite
 * d'un evenement de livraison. AFTER_COMMIT, sinon la notification pointerait
 * vers un message que la base n'a pas encore.
 */
@Component
public class SupportMessageEventListener {

    private final NotificationDispatcher notificationDispatcher;

    public SupportMessageEventListener(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onSupportMessage(SupportMessageCreatedEvent event) {
        if (event.getAuthorType() != SupportMessageAuthorType.ADMIN) {
            return;
        }
        notificationDispatcher.notifyUser(
                event.getOwnerUserId(),
                "Le support vous a repondu",
                "Ouvrez votre demande pour lire la reponse.",
                Map.of("type", "SUPPORT_MESSAGE",
                        "ticketId", event.getTicketId().toString()));
    }
}
