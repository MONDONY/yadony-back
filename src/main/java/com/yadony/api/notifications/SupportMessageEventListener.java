package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
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
 * Chaque message d'un admin produit une entree du centre de notifications et
 * un push (type {@code SUPPORT_MESSAGE}, donnee {@code ticketId}). Le push suit
 * l'interrupteur « Messages » des preferences ; l'entree in-app, elle, est
 * toujours enregistree. Premier message d'une conversation ouverte par le
 * support : texte distinct, qui cite le sujet.
 *
 * <p>Push simple, sans repli SMS : une reponse du support n'a pas la criticite
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
        Messages m = notificationDispatcher.messagesFor(event.getOwnerUserId());
        NotificationText text = event.isStartedByAdmin()
                ? NotificationTexts.supportStarted(m, event.getSubject())
                : NotificationTexts.supportReply(m);
        // notifyUser persiste l'entree du centre de notifications PUIS pousse,
        // en un seul appel : jamais de push sans entree in-app, ni l'inverse.
        notificationDispatcher.notifyUser(
                event.getOwnerUserId(),
                text.title(),
                text.body(),
                Map.of("type", "SUPPORT_MESSAGE",
                        "ticketId", event.getTicketId().toString()));
    }
}
