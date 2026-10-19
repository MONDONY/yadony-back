package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.payments.events.BankPayoutFailedEvent;
import com.yadony.api.payments.events.BankPayoutPaidEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Virement Stripe vers la banque du voyageur : arrivé ou échoué. In-app et push, dans la langue du
 * voyageur. AFTER_COMMIT : un suivi annulé n'annonce rien ; l'unicité par payout est garantie en
 * amont ({@code bank_payout_tracking.notified_key}).
 */
@Component
public class BankPayoutNotificationListener {

    static final String TYPE_PAID = "BANK_PAYOUT_PAID";
    static final String TYPE_FAILED = "BANK_PAYOUT_FAILED";

    private final NotificationDispatcher notificationDispatcher;

    public BankPayoutNotificationListener(NotificationDispatcher notificationDispatcher) {
        this.notificationDispatcher = notificationDispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaid(BankPayoutPaidEvent event) {
        Messages m = notificationDispatcher.messagesFor(event.travelerId());
        NotificationText text = NotificationTexts.bankPayoutPaid(m,
                event.currency() == null ? "" : NotificationTexts.amount(event.amount(), event.currency()));
        notificationDispatcher.notifyUser(event.travelerId(), text.title(), text.body(), data(TYPE_PAID, event.payoutId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(BankPayoutFailedEvent event) {
        Messages m = notificationDispatcher.messagesFor(event.travelerId());
        NotificationText text = NotificationTexts.bankPayoutFailed(m);
        Map<String, String> data = data(TYPE_FAILED, event.payoutId());
        if (event.failureCode() != null) {
            data.put("failureCode", event.failureCode());
        }
        notificationDispatcher.notifyUser(event.travelerId(), text.title(), text.body(), data);
    }

    private static Map<String, String> data(String type, String payoutId) {
        Map<String, String> data = new HashMap<>();
        data.put("type", type);
        if (payoutId != null) {
            data.put("payoutId", payoutId);
        }
        return data;
    }
}
