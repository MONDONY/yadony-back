package com.yadony.api.payments.supplement;

import com.yadony.api.payments.supplement.events.BidSupplementClosedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Suites du rail espèces, après le commit qui les décide et hors de toute transaction (appel
 * Stripe possible) : la commission du supplément est rendue au voyageur quand la demande se
 * ferme sans aboutir (refus, retrait, échéance, colis terminé), ou quand un supplément espèces
 * accepté finit sur un colis non livré. Un échec est laissé au filet
 * ({@link BidSupplementCashReconciliationJob}).
 */
@Component
public class BidSupplementCashListener {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCashListener.class);

    private final BidSupplementCashRefunds refunds;

    public BidSupplementCashListener(BidSupplementCashRefunds refunds) {
        this.refunds = refunds;
    }

    /** Demande close sans aboutir : commission rendue, ou PaymentIntent de commission en 3DS annulée. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onClosed(BidSupplementClosedEvent e) {
        if (e.rail() == BidSupplementRail.CASH) {
            settle(e.supplementId());
        }
    }

    /** Supplément espèces accepté sur un colis terminé sans livraison, ou commission arrivée trop tard. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRefundRequested(BidSupplementRefundRequestedEvent e) {
        if (e.rail() == BidSupplementRail.CASH) {
            settle(e.supplementId());
        }
    }

    private void settle(java.util.UUID supplementId) {
        try {
            refunds.settle(supplementId);
        } catch (RuntimeException ex) {
            log.error("Commission du supplément espèces {} : remboursement en échec", supplementId, ex);
        }
    }
}
