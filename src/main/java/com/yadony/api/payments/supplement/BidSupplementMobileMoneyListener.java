package com.yadony.api.payments.supplement;

import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Suites pawaPay du supplément mobile money, après le commit qui les décide et hors de toute
 * transaction (appel réseau) : chaque étape ouvre ses transactions courtes via
 * {@link BidSupplementMobileMoneyLedger}. Un échec est laissé au filet
 * ({@link BidSupplementMobileMoneyReconciliationJob}). Un dépôt ne s'annule pas : une demande close
 * pendant le paiement n'a rien à défaire ici, un dépôt qui aboutirait encore serait rendu.
 */
@Component
public class BidSupplementMobileMoneyListener {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementMobileMoneyListener.class);

    private final BidSupplementMobileMoneyPayouts payouts;
    private final BidSupplementMobileMoneyRefunds refunds;

    public BidSupplementMobileMoneyListener(BidSupplementMobileMoneyPayouts payouts,
                                            BidSupplementMobileMoneyRefunds refunds) {
        this.payouts = payouts;
        this.refunds = refunds;
    }

    /** Argent à rendre à l'expéditeur. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRefundRequested(BidSupplementRefundRequestedEvent e) {
        if (e.rail() != BidSupplementRail.PAWAPAY) {
            return;
        }
        try {
            refunds.refund(e.supplementId());
        } catch (RuntimeException ex) {
            log.error("Remboursement mobile money du supplément {} en échec", e.supplementId(), ex);
        }
    }

    /**
     * Versement principal CONFIRMÉ par pawaPay (publié par {@code MobileMoneyPayoutOutcomeListener}
     * sur le payout principal {@code COMPLETED}) : le supplément mobile money payé suit. Un rejeu ne
     * trouve plus de supplément {@code PAID} et reste sans effet.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onPaymentReleased(PaymentReleasedEvent e) {
        if (!e.isMobileMoney() || e.getBidId() == null) {
            return;
        }
        try {
            payouts.payoutForBid(e.getBidId());
        } catch (RuntimeException ex) {
            log.error("Versement mobile money du supplément du colis {} en échec", e.getBidId(), ex);
        }
    }
}
