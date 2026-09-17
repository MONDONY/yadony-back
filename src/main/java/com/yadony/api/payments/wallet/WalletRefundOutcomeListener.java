package com.yadony.api.payments.wallet;

import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Fait avancer les items de remboursement wallet du rail pawaPay. Trois entrées, toutes en
 * {@code AFTER_COMMIT} + {@code REQUIRES_NEW} (règle 18) :
 * <ul>
 *   <li>{@link #onInitiationRequested} : l'opération liée à l'item est committée, on appelle
 *       pawaPay ({@link WalletPawapayRefundIssuer#initiate}). La transaction ouverte ici n'écrit
 *       pas le lien, elle ne peut donc pas l'annuler ; elle ne sert qu'au rejet synchrone.</li>
 *   <li>{@link #onCompleted} / {@link #onFailed} : issue finale d'une opération {@code WALLET_REFUND}
 *       (REFUND ou PAYOUT), publiée une seule fois par {@code PawapayOperationService#apply}
 *       (callback ou poller). Les autres purposes (paiement de colis, recharge) sont ignorés.</li>
 * </ul>
 * La transition vit dans {@link WalletPawapayRefundIssuer#applyPawapayOutcome} (idempotente),
 * puis la demande est résolue si tous ses items sont terminaux (débit du brut au wallet, ticket
 * enfant pour les items FAILED).
 */
@Component
public class WalletRefundOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(WalletRefundOutcomeListener.class);

    private final WalletPawapayRefundIssuer issuer;
    private final WalletSelfRefundService walletSelfRefundService;

    public WalletRefundOutcomeListener(WalletPawapayRefundIssuer issuer, WalletSelfRefundService walletSelfRefundService) {
        this.issuer = issuer;
        this.walletSelfRefundService = walletSelfRefundService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onInitiationRequested(WalletPawapayRefundInitiationEvent event) {
        issuer.initiate(event.itemId(), event.operationId())
                .ifPresent(item -> walletSelfRefundService.resolveIfComplete(item.getRefundRequestId()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PawapayOperationCompletedEvent event) {
        if (concernsWalletRefund(event.purpose(), event.kind())) {
            settle(event.kind(), event.operationId(), true, null);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(PawapayOperationFailedEvent event) {
        if (concernsWalletRefund(event.purpose(), event.kind())) {
            settle(event.kind(), event.operationId(), false, event.failureCode());
        }
    }

    private static boolean concernsWalletRefund(PawapayOperationPurpose purpose, PawapayOperationKind kind) {
        return purpose == PawapayOperationPurpose.WALLET_REFUND
                && (kind == PawapayOperationKind.REFUND || kind == PawapayOperationKind.PAYOUT);
    }

    private void settle(PawapayOperationKind kind, UUID operationId, boolean completed, String failureCode) {
        Optional<WalletRefundRequestItemEntity> item = issuer.findItem(kind, operationId);
        if (item.isEmpty()) {
            log.warn("Remboursement wallet pawaPay : aucun item lie a l'operation {} {}", kind, operationId);
            return;
        }
        issuer.applyPawapayOutcome(item.get(), kind, completed, failureCode);
        walletSelfRefundService.resolveIfComplete(item.get().getRefundRequestId());
    }
}
