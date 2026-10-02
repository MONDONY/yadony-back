package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.calls.CallNotifications;
import com.yadony.api.calls.CallStatus;
import com.yadony.api.calls.events.CallEndedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/** Push « Appel manqué » à l'appelé, avec la conversation pour rappeler. */
@Component
public class CallMissedNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(CallMissedNotificationListener.class);

    private final NotificationDispatcher dispatcher;
    private final UserRepository users;

    public CallMissedNotificationListener(NotificationDispatcher dispatcher, UserRepository users) {
        this.dispatcher = dispatcher;
        this.users = users;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallEnded(CallEndedEvent event) {
        if (event.status() != CallStatus.MISSED) return;
        try {
            String firstName = users.findById(event.callerId()).map(UserEntity::getFirstName).orElse(null);
            var text = NotificationTexts.callMissed(dispatcher.messagesFor(event.calleeId()), firstName);
            dispatcher.notifyUnlessBlocked(event.calleeId(), event.callerId(), text.title(), text.body(),
                    Map.of("type", CallNotifications.MISSED, "conversationId", event.conversationId().toString()));
        } catch (Exception e) {
            log.warn("Push d'appel manqué {} impossible : {}", event.callId(), e.toString());
        }
    }
}
