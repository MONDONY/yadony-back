package com.yadony.api.payments.fee;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionOperations;

import java.util.UUID;

/**
 * Retenue d'annulation réglée ({@link CancellationFeeRetainedEvent}, publié dans la transaction du remboursement) :
 * le net part au voyageur dès le commit de ce remboursement, par {@link CancellationFeePayoutService#payOut}.
 *
 * <p>{@code AFTER_COMMIT} : un remboursement annulé ne verse jamais rien. Pas de transaction propre autour de
 * l'écouteur (contrat des écouteurs qui appellent Stripe, PROD_CHECKLIST 9.23) : la lecture de la retenue a sa
 * transaction neuve, le versement ouvre les siennes autour de l'appel Stripe. Synchrone : le remboursement tourne
 * déjà hors de la requête de l'utilisateur (écouteurs asynchrones). Un échec ici ne perd rien : le passage horaire
 * ({@link CancellationFeePayoutScheduler}) reprend les lignes restées {@code RETAINED} ou en attente.
 */
@Component
public class CancellationFeePayoutListener {

    private static final Logger log = LoggerFactory.getLogger(CancellationFeePayoutListener.class);

    private final CancellationFeePayoutPort port;
    private final CancellationFeePayoutService payoutService;
    private final TransactionOperations transaction;

    @Autowired
    public CancellationFeePayoutListener(CancellationFeePayoutPort port, CancellationFeePayoutService payoutService,
                                         PlatformTransactionManager transactionManager) {
        this(port, payoutService, CancellationFeePayoutService.requiresNew(transactionManager));
    }

    CancellationFeePayoutListener(CancellationFeePayoutPort port, CancellationFeePayoutService payoutService,
                                  TransactionOperations transaction) {
        this.port = port;
        this.payoutService = payoutService;
        this.transaction = transaction;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onFeeRetained(CancellationFeeRetainedEvent event) {
        UUID feeId;
        try {
            // Transaction neuve : celle du remboursement, terminée, est encore liée au fil.
            feeId = transaction.execute(tx -> port.findPayableByBid(event.bidId())
                    .map(PayableCancellationFee::feeId)
                    .orElse(null));
        } catch (RuntimeException e) {
            log.error("Dédommagement du colis {} : retenue illisible après le règlement, le passage horaire la reprendra",
                    event.bidId(), e);
            return;
        }
        if (feeId == null) {
            log.debug("Dédommagement du colis {} : rien à verser (déjà versé ou clos)", event.bidId());
            return;
        }
        payoutService.payOut(feeId);
    }
}
