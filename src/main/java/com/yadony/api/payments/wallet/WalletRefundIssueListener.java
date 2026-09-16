package com.yadony.api.payments.wallet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Émet les remboursements Stripe d'une demande automatique APRÈS le commit de la transaction
 * qui l'a créée. Avant, {@code Refund.create} partait dans cette même transaction : un commit
 * refusé ou une transaction englobante en rollback laissait le client remboursé chez Stripe
 * sans aucune trace en base (webhook orphelin, wallet jamais débité, solde restitué
 * dépensable).
 *
 * <p>Synchrone (pas de {@code @Async}) : l'émission se fait dans le fil de la requête, juste
 * après le commit. Une erreur est journalisée sans remonter : la demande est déjà committée,
 * et {@link WalletRefundIssueRecoveryScheduler} reprendra les items restés PENDING.
 */
@Component
public class WalletRefundIssueListener {

    private static final Logger log = LoggerFactory.getLogger(WalletRefundIssueListener.class);

    private final WalletSelfRefundService walletSelfRefundService;

    public WalletRefundIssueListener(WalletSelfRefundService walletSelfRefundService) {
        this.walletSelfRefundService = walletSelfRefundService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onItemsCreated(WalletRefundItemsCreatedEvent event) {
        try {
            walletSelfRefundService.issuePendingItems(event.refundRequestId());
        } catch (RuntimeException e) {
            log.error("Emission Stripe apres commit impossible pour la demande wallet {} : "
                    + "la reprise planifiee la rattrapera", event.refundRequestId(), e);
        }
    }
}
