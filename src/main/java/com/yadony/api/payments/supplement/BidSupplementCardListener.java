package com.yadony.api.payments.supplement;

import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementClosedEvent;
import com.yadony.api.payments.supplement.events.BidSupplementRefundRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Suites Stripe du supplément carte, après le commit qui les décide et hors de toute transaction
 * (appel réseau, PROD_CHECKLIST 9.23) : chaque étape ouvre ses transactions courtes via
 * {@link BidSupplementCardLedger}. Un échec est laissé au filet ({@link BidSupplementCardReconciliationJob}).
 */
@Component
public class BidSupplementCardListener {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementCardListener.class);

    private final BidSupplementCardRail rail;
    private final BidSupplementCardPayouts payouts;
    private final BidSupplementCardRefunds refunds;

    public BidSupplementCardListener(BidSupplementCardRail rail, BidSupplementCardPayouts payouts,
                                     BidSupplementCardRefunds refunds) {
        this.rail = rail;
        this.payouts = payouts;
        this.refunds = refunds;
    }

    /** Demande close pendant le paiement (refus, échéance, colis terminé) : PaymentIntent annulé. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onClosed(BidSupplementClosedEvent e) {
        if (e.rail() != BidSupplementRail.STRIPE || e.previousStatus() != BidSupplementStatus.PAYMENT_PENDING) {
            return;
        }
        try {
            rail.cancelPendingPayment(e.supplementId());
        } catch (RuntimeException ex) {
            log.error("Annulation du paiement du supplément {} en échec", e.supplementId(), ex);
        }
    }

    /** Argent à rendre à l'expéditeur. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRefundRequested(BidSupplementRefundRequestedEvent e) {
        if (e.rail() != BidSupplementRail.STRIPE) {
            return;
        }
        try {
            refunds.refund(e.supplementId());
        } catch (RuntimeException ex) {
            log.error("Remboursement du supplément {} en échec", e.supplementId(), ex);
        }
    }

    /**
     * Versement principal fait (rail carte, publié après le Transfer principal : livraison, colis
     * non réclamé, rattrapage, libération forcée admin) : le supplément carte payé suit. Un rejeu
     * de l'événement ne trouve plus de supplément {@code PAID} et reste sans effet.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onPaymentReleased(PaymentReleasedEvent e) {
        if (e.isMobileMoney() || e.getBidId() == null) {
            return;
        }
        try {
            payouts.payoutForBid(e.getBidId());
        } catch (RuntimeException ex) {
            log.error("Versement du supplément du colis {} en échec", e.getBidId(), ex);
        }
    }
}
