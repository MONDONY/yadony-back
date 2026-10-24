package com.yadony.api.payments.amendment;

import com.yadony.api.payments.amendment.events.BidAmendmentClosedEvent;
import com.yadony.api.payments.amendment.events.BidAmendmentRefundRequestedEvent;
import com.yadony.api.payments.supplement.BidSupplementRail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Suites Stripe du rail carte, APRÈS le commit qui les décide et hors de toute transaction (appel réseau) : une
 * clôture annulée par un rollback (scan de remise qui échoue après coup) n'annule jamais une autorisation, et rien
 * n'est appelé chez Stripe sous un verrou. Chaque étape ouvre ses transactions courtes via
 * {@link BidAmendmentCardLedger}.
 */
@Component
public class BidAmendmentCardListener {

    private static final Logger log = LoggerFactory.getLogger(BidAmendmentCardListener.class);

    private final BidAmendmentCardRail rail;
    private final BidAmendmentCardRefunds refunds;

    public BidAmendmentCardListener(BidAmendmentCardRail rail, BidAmendmentCardRefunds refunds) {
        this.rail = rail;
        this.refunds = refunds;
    }

    /** Demande close sans aboutir alors que le montant était bloqué ou en passe de l'être : autorisation rendue. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onClosed(BidAmendmentClosedEvent e) {
        if (e.rail() != BidSupplementRail.STRIPE || e.previousStatus() == null || !e.previousStatus().isOpen()) {
            return;
        }
        try {
            rail.releaseAuthorization(e.amendmentId());
        } catch (RuntimeException ex) {
            log.error("Autorisation carte de la modification {} : annulation en échec", e.amendmentId(), ex);
        }
    }

    /** Argent à rendre à l'expéditeur : modification défaite (non apportée, colis terminé), capture tardive. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    public void onRefundRequested(BidAmendmentRefundRequestedEvent e) {
        if (e.rail() != BidSupplementRail.STRIPE) {
            return;
        }
        try {
            refunds.refund(e.amendmentId());
        } catch (RuntimeException ex) {
            log.error("Remboursement carte de la modification {} en échec", e.amendmentId(), ex);
        }
    }
}
