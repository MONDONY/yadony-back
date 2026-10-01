package com.yadony.api.addressbook.invitation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/**
 * Prévient l'invité, en asynchrone : l'envoi d'une invitation doit répondre dans le même
 * temps qu'un compte existe ou non, le push ne pèse donc jamais sur la réponse HTTP.
 */
@Component
public class RecipientInvitationNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(RecipientInvitationNotificationListener.class);

    private final UserRepository userRepository;
    private final NotificationDispatcher notificationDispatcher;

    public RecipientInvitationNotificationListener(UserRepository userRepository,
                                                   NotificationDispatcher notificationDispatcher) {
        this.userRepository = userRepository;
        this.notificationDispatcher = notificationDispatcher;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInvitationCreated(RecipientInvitationCreatedEvent event) {
        try {
            String inviterFirstName = userRepository.findById(event.inviterUserId())
                    .map(UserEntity::getFirstName)
                    .orElse(null);
            var text = NotificationTexts.recipientInvitation(
                    notificationDispatcher.messagesFor(event.inviteeUserId()), inviterFirstName);
            notificationDispatcher.notifyUser(event.inviteeUserId(), text.title(), text.body(),
                    Map.of("type", RecipientInvitationNotifications.INVITATION,
                            "invitationId", event.invitationId().toString()));
        } catch (Exception e) {
            log.warn("Notification d'invitation {} impossible : {}", event.invitationId(), e.toString());
        }
    }
}
