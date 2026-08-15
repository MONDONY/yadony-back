package com.yadony.api.notifications;

import com.yadony.api.matching.events.HandoverAlertEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Envoie le rappel H-2 après le commit du marqueur d'idempotence du scheduler. */
@Component
public class HandoverAlertNotificationListener {

    private final NotificationDispatcher dispatcher;

    public HandoverAlertNotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onHandoverAlert(HandoverAlertEvent event) {
        dispatcher.onHandoverAlert(event);
    }
}
