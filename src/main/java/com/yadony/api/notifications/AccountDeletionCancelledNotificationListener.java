package com.yadony.api.notifications;

import com.yadony.api.auth.events.AccountDeletionCancelledByAdminEvent;
import com.yadony.api.common.i18n.Messages;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Prévient l'utilisateur que l'équipe a annulé sa demande de suppression de compte : in-app
 * et push, dans sa langue, sans le motif interne. AFTER_COMMIT : une annulation annulée ne
 * doit rien annoncer.
 */
@Component
public class AccountDeletionCancelledNotificationListener {

    static final String TYPE = "ACCOUNT_DELETION_CANCELLED";

    private final NotificationDispatcher notificationDispatcher;

    public AccountDeletionCancelledNotificationListener(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCancelled(AccountDeletionCancelledByAdminEvent event) {
        Messages m = notificationDispatcher.messagesFor(event.userId());
        NotificationText text = NotificationTexts.accountDeletionCancelledByAdmin(m);
        notificationDispatcher.notifyUser(event.userId(), text.title(), text.body(), Map.of("type", TYPE));
    }
}
