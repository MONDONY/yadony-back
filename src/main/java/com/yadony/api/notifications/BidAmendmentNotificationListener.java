package com.yadony.api.notifications;

import com.yadony.api.common.i18n.Messages;
import com.yadony.api.matching.TripTimezones;
import com.yadony.api.payments.amendment.BidAmendmentClosedReason;
import com.yadony.api.payments.amendment.BidAmendmentStatus;
import com.yadony.api.payments.amendment.events.BidAmendmentAcceptedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentClosedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentReminderDueEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRequestedEvent;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Notifications de la modification d'un colis accepté, dans la langue du destinataire, après commit.
 * Voie système ({@link NotificationDispatcher#notifyUser}) : l'issue d'une transaction en cours ne dépend
 * d'aucune règle de blocage. Le voyageur ne voit que son net, l'expéditeur que le brut, et seulement dans
 * la notification de remboursement ; aucun nom, aucun numéro.
 */
@Component
public class BidAmendmentNotificationListener {

    private final NotificationDispatcher dispatcher;

    public BidAmendmentNotificationListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRequested(BidAmendmentRequestedEvent e) {
        Messages m = dispatcher.messagesFor(e.travelerId());
        NotificationText text = NotificationTexts.bidAmendmentRequested(m,
                NotificationTexts.amount(e.netAmount(), e.currency()),
                e.expiresAt().atZone(ZoneOffset.UTC).withZoneSameInstant(TripTimezones.zoneOf(e.timezone())));
        notify(e.travelerId(), text, BidAmendmentNotifications.REQUESTED, e.bidId(), e.amendmentId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onReminderDue(BidAmendmentReminderDueEvent e) {
        notify(e.travelerId(), NotificationTexts.bidAmendmentReminder(dispatcher.messagesFor(e.travelerId())),
                BidAmendmentNotifications.REMINDER, e.bidId(), e.amendmentId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onAccepted(BidAmendmentAcceptedEvent e) {
        Messages m = dispatcher.messagesFor(e.senderId());
        NotificationText text;
        if (e.needsPayment()) {
            text = NotificationTexts.bidAmendmentAcceptedPayNow(m);
        } else if (e.rail() == BidSupplementRail.STRIPE) {
            text = NotificationTexts.bidAmendmentAcceptedCard(m);
        } else if (e.rail() == BidSupplementRail.CASH) {
            text = NotificationTexts.bidAmendmentAcceptedCash(m);
        } else {
            text = NotificationTexts.bidAmendmentAccepted(m);
        }
        notify(e.senderId(), text, BidAmendmentNotifications.ACCEPTED, e.bidId(), e.amendmentId());
    }

    /** Toutes les clôtures sans aboutir : l'événement est scellé, chaque variante a ses destinataires. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onClosed(BidAmendmentClosedEvent e) {
        switch (e) {
            case BidAmendmentClosedEvent.Declined d -> notify(d.senderId(),
                    NotificationTexts.bidAmendmentDeclined(dispatcher.messagesFor(d.senderId()),
                            d.rail() == BidSupplementRail.STRIPE),
                    BidAmendmentNotifications.DECLINED, d.bidId(), d.amendmentId());
            case BidAmendmentClosedEvent.Expired x -> onExpired(x);
            case BidAmendmentClosedEvent.Cancelled c -> onCancelled(c);
            case BidAmendmentClosedEvent.NotBrought n -> notify(n.senderId(),
                    NotificationTexts.bidAmendmentNotBrought(dispatcher.messagesFor(n.senderId()),
                            n.rail() != BidSupplementRail.CASH),
                    BidAmendmentNotifications.NOT_BROUGHT, n.bidId(), n.amendmentId());
        }
    }

    private void onExpired(BidAmendmentClosedEvent.Expired e) {
        boolean unpaid = e.closedReason() == BidAmendmentClosedReason.PAYMENT_NOT_RECEIVED;
        notify(e.travelerId(), NotificationTexts.bidAmendmentExpiredForTraveler(
                        dispatcher.messagesFor(e.travelerId()), unpaid),
                BidAmendmentNotifications.EXPIRED, e.bidId(), e.amendmentId());
        notify(e.senderId(), NotificationTexts.bidAmendmentExpiredForSender(dispatcher.messagesFor(e.senderId()),
                        unpaid, e.rail() == BidSupplementRail.STRIPE),
                BidAmendmentNotifications.EXPIRED, e.bidId(), e.amendmentId());
    }

    private void onCancelled(BidAmendmentClosedEvent.Cancelled e) {
        boolean captureFailed = e.closedReason() == BidAmendmentClosedReason.CAPTURE_FAILED;
        if (captureFailed) {
            notify(e.senderId(), NotificationTexts.bidAmendmentCancelledCaptureForSender(
                            dispatcher.messagesFor(e.senderId())),
                    BidAmendmentNotifications.CANCELLED, e.bidId(), e.amendmentId());
        }
        if (e.previousStatus() == BidAmendmentStatus.AUTHORIZATION_PENDING) {
            // Jamais présentée au voyageur (carte encore en autorisation) : il n'a rien à apprendre.
            return;
        }
        Messages m = dispatcher.messagesFor(e.travelerId());
        NotificationText text = switch (e.closedReason()) {
            case CAPTURE_FAILED -> NotificationTexts.bidAmendmentCancelledCaptureForTraveler(m);
            case CANCELLED_BY_SENDER -> NotificationTexts.bidAmendmentCancelledBySenderForTraveler(m);
            case COMMISSION_NOT_CONFIRMED -> NotificationTexts.bidAmendmentCancelledCommissionForTraveler(m);
            default -> NotificationTexts.bidAmendmentCancelledForTraveler(m);
        };
        notify(e.travelerId(), text, BidAmendmentNotifications.CANCELLED, e.bidId(), e.amendmentId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRefunded(BidAmendmentRefundedEvent e) {
        NotificationText text = NotificationTexts.bidAmendmentRefunded(dispatcher.messagesFor(e.recipientId()),
                NotificationTexts.amount(e.amount(), e.currency()), e.commissionOnly());
        notify(e.recipientId(), text, BidAmendmentNotifications.REFUNDED, e.bidId(), e.amendmentId());
    }

    private void notify(UUID userId, NotificationText text, String type, UUID bidId, UUID amendmentId) {
        dispatcher.notifyUser(userId, text.title(), text.body(),
                Map.of("type", type, "bidId", bidId.toString(), "amendmentId", amendmentId.toString()));
    }
}
