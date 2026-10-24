package com.yadony.api.payments.amendment;

import com.yadony.api.payments.amendment.events.BidAmendmentClosedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundRequestedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentStaleCommissionEvent;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Suites du rail espèces, après le commit qui les décide et hors de toute transaction (appel Stripe possible) :
 * la commission de la modification est rendue au voyageur quand la modification se ferme sans aboutir (refus,
 * annulation, échéance, « pas apportée »), ou quand sa commission arrive après coup. Rien n'est prélevé tant que
 * le voyageur n'a pas accepté : {@link BidAmendmentCashRefunds#settle} ne fait alors rien, et défait au besoin la
 * PaymentIntent de commission laissée en 3DS. Un échec est laissé au filet (B6).
 */
@Component
public class BidAmendmentCashListener {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentCashListener.class);

    private final BidAmendmentCashRefunds refunds;

    public BidAmendmentCashListener(BidAmendmentCashRefunds refunds) {
        this.refunds = refunds;
    }

    /** Modification close sans aboutir : commission rendue, ou PaymentIntent de commission en 3DS annulée. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onClosed(BidAmendmentClosedEvent e) {
        if (e.rail() == BidSupplementRail.CASH) {
            settle(e.amendmentId());
        }
    }

    /** Commission arrivée trop tard sur une modification close. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRefundRequested(BidAmendmentRefundRequestedEvent e) {
        if (e.rail() == BidSupplementRail.CASH) {
            settle(e.amendmentId());
        }
    }

    /** PaymentIntent de commission aboutie hors de l'essai courant : rendue, PaymentIntent par PaymentIntent. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onStaleCommission(BidAmendmentStaleCommissionEvent e) {
        try {
            refunds.refundStale(e.amendmentId(), e.bidId(), e.paymentIntentId());
        } catch (RuntimeException ex) {
            log.error("Commission {} de la modification {} : remboursement en échec", e.paymentIntentId(),
                    e.amendmentId(), ex);
        }
    }

    private void settle(UUID amendmentId) {
        try {
            refunds.settle(amendmentId);
        } catch (RuntimeException ex) {
            log.error("Commission de la modification espèces {} : remboursement en échec", amendmentId, ex);
        }
    }
}
