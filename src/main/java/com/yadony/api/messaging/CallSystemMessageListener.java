package com.yadony.api.messaging;

import com.yadony.api.calls.events.CallEndedEvent;
import com.yadony.api.common.i18n.Messages;
import com.yadony.api.common.i18n.MessagesResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Trace d'un appel audio dans la conversation (« 📞 Appel audio · 4 min »), dans la langue de l'appelé. */
@Component
public class CallSystemMessageListener {

    private static final Logger log = LoggerFactory.getLogger(CallSystemMessageListener.class);

    private final FirestoreService firestore;
    private final MessagesResolver messages;

    public CallSystemMessageListener(FirestoreService firestore, MessagesResolver messages) {
        this.firestore = firestore;
        this.messages = messages;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCallEnded(CallEndedEvent event) {
        if (event.firestoreConversationId() == null) return;
        try {
            firestore.addSystemMessage(event.firestoreConversationId(), text(messages.forUser(event.calleeId()), event));
        } catch (Exception e) {
            log.warn("Message système d'appel {} impossible : {}", event.callId(), e.toString());
        }
    }

    private static String text(Messages m, CallEndedEvent event) {
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
