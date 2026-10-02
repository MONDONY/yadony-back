package com.yadony.api.calls;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.calls.events.CallEndedEvent;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.notifications.NotificationTexts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;

/** Trace d'un appel dans la conversation (langue de l'appelé) et push « Appel manqué ». */
@Component
public class CallEndedListener {

    private static final Logger log = LoggerFactory.getLogger(CallEndedListener.class);

    private final FirestoreService firestore;
    private final NotificationDispatcher dispatcher;
    private final UserRepository users;

    public CallEndedListener(FirestoreService firestore, NotificationDispatcher dispatcher, UserRepository users) {
        this.firestore = firestore;
        this.dispatcher = dispatcher;
        this.users = users;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallEnded(CallEndedEvent event) {
        Messages m = dispatcher.messagesFor(event.calleeId());
        if (event.firestoreConversationId() != null) {
            try {
                firestore.addSystemMessage(event.firestoreConversationId(), systemText(m, event));
            } catch (Exception e) {
                log.warn("Message système d'appel {} impossible : {}", event.callId(), e.toString());
            }
        }
        if (event.status() == CallStatus.MISSED) {
            try {
                String firstName = users.findById(event.callerId()).map(UserEntity::getFirstName).orElse(null);
                var text = NotificationTexts.callMissed(m, firstName);
                dispatcher.notifyUnlessBlocked(event.calleeId(), event.callerId(), text.title(), text.body(),
                        Map.of("type", CallNotifications.MISSED, "conversationId", event.conversationId().toString()));
            } catch (Exception e) {
                log.warn("Push d'appel manqué {} impossible : {}", event.callId(), e.toString());
            }
        }
    }

    static String systemText(Messages m, CallEndedEvent event) {
        return switch (event.status()) {
            case ENDED -> m.get("call.system.ended", duration(m, event.durationSeconds()));
            case REJECTED -> m.get("call.system.rejected");
            default -> m.get("call.system.missed");
        };
    }

    private static String duration(Messages m, Integer seconds) {
        int s = seconds == null ? 0 : seconds;
        return s < 60 ? m.get("call.duration.seconds", s) : m.get("call.duration.minutes", s / 60);
    }
}
