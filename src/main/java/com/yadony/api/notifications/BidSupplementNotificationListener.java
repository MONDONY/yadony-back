package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.matching.TripTimezones;
import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.events.BidSupplementDeclinedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementExpiredEvent;
import com.yadony.api.payments.supplement.events.BidSupplementPaidEvent;
import com.yadony.api.payments.supplement.events.BidSupplementPaidOutEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRequestedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementWithdrawnEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Notifications du supplément de prix (plan §7.1), dans la langue du destinataire, après commit.
 * Voie système ({@link NotificationDispatcher#notifyUser}) : l'issue d'une transaction en cours ne
 * dépend d'aucune règle de blocage. L'expéditeur ne voit que le brut, jamais le net.
 */
@Component
public class BidSupplementNotificationListener {

    private final NotificationDispatcher dispatcher;

    public BidSupplementNotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRequested(BidSupplementRequestedEvent e) {
        Messages m = dispatcher.messagesFor(e.senderId());
        NotificationText text = NotificationTexts.bidSupplementRequested(m,
                NotificationTexts.amount(e.grossAmount(), e.currency()),
                e.expiresAt().atZone(ZoneOffset.UTC).withZoneSameInstant(TripTimezones.zoneOf(e.timezone())));
        notify(e.senderId(), text, BidSupplementNotifications.REQUESTED, e.bidId(), e.supplementId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onPaid(BidSupplementPaidEvent e) {
        NotificationText text = NotificationTexts.bidSupplementPaid(dispatcher.messagesFor(e.travelerId()),
                NotificationTexts.amount(e.grossAmount(), e.currency()), e.rail() == BidSupplementRail.CASH);
        notify(e.travelerId(), text, BidSupplementNotifications.PAID, e.bidId(), e.supplementId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onDeclined(BidSupplementDeclinedEvent e) {
        notify(e.travelerId(), NotificationTexts.bidSupplementDeclined(dispatcher.messagesFor(e.travelerId())),
                BidSupplementNotifications.DECLINED, e.bidId(), e.supplementId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onExpired(BidSupplementExpiredEvent e) {
        notify(e.travelerId(), NotificationTexts.bidSupplementExpiredForTraveler(dispatcher.messagesFor(e.travelerId())),
                BidSupplementNotifications.EXPIRED, e.bidId(), e.supplementId());
        notify(e.senderId(), NotificationTexts.bidSupplementExpiredForSender(dispatcher.messagesFor(e.senderId())),
                BidSupplementNotifications.EXPIRED, e.bidId(), e.supplementId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onWithdrawn(BidSupplementWithdrawnEvent e) {
        notify(e.senderId(), NotificationTexts.bidSupplementWithdrawn(dispatcher.messagesFor(e.senderId())),
                BidSupplementNotifications.WITHDRAWN, e.bidId(), e.supplementId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRefunded(BidSupplementRefundedEvent e) {
        NotificationText text = NotificationTexts.bidSupplementRefunded(dispatcher.messagesFor(e.recipientId()),
                NotificationTexts.amount(e.amount(), e.currency()), e.commissionOnly());
        notify(e.recipientId(), text, BidSupplementNotifications.REFUNDED, e.bidId(), e.supplementId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onPaidOut(BidSupplementPaidOutEvent e) {
        NotificationText text = NotificationTexts.bidSupplementPaidOut(dispatcher.messagesFor(e.travelerId()),
                NotificationTexts.amount(e.netAmount(), e.currency()));
        notify(e.travelerId(), text, BidSupplementNotifications.PAID_OUT, e.bidId(), e.supplementId());
    }

    private void notify(UUID userId, NotificationText text, String type, UUID bidId, UUID supplementId) {
        dispatcher.notifyUser(userId, text.title(), text.body(),
                Map.of("type", type, "bidId", bidId.toString(), "supplementId", supplementId.toString()));
    }
}
