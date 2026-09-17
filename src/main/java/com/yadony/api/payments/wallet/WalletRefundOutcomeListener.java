package com.yadony.api.payments.wallet;

import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.util.Map;
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
 *
 * <p><b>Verrous.</b> La demande est verrouillée AVANT l'item, dans le même ordre que
 * {@code WalletSelfRefundService#issuePendingItems}. Sans le verrou de la demande, deux issues
 * simultanées sur deux items d'une même demande verrouillaient chacune leur item, lisaient
 * l'autre encore PROCESSING, et personne ne résolvait : wallet jamais débité.
 *
 * <p><b>Erreurs.</b> Ces événements ne sont publiés qu'une fois : une exception ici (sauvegarde,
 * résolution, réservation du versement de repli) perdrait l'issue sans trace et laisserait l'item
 * PROCESSING. Comme {@code WalletTopupOutcomeListener}, le corps alerte un administrateur
 * ({@link #ALERT_CODE}) puis repropage : la transaction est annulée, jamais commitée à moitié.
 */
@Component
public class WalletRefundOutcomeListener {

    static final String ALERT_CODE = "WALLET_REFUND_OUTCOME_FAILED";

    private static final Logger log = LoggerFactory.getLogger(WalletRefundOutcomeListener.class);

    private final WalletPawapayRefundIssuer issuer;
    private final WalletSelfRefundService walletSelfRefundService;
    private final WalletRefundRequestRepository refundRequestRepository;
    private final WalletRefundRequestItemRepository itemRepository;
    private final AdminAlertService adminAlertService;

    public WalletRefundOutcomeListener(WalletPawapayRefundIssuer issuer, WalletSelfRefundService walletSelfRefundService,
                                       WalletRefundRequestRepository refundRequestRepository,
                                       WalletRefundRequestItemRepository itemRepository,
                                       AdminAlertService adminAlertService) {
        this.issuer = issuer;
        this.walletSelfRefundService = walletSelfRefundService;
        this.refundRequestRepository = refundRequestRepository;
        this.itemRepository = itemRepository;
        this.adminAlertService = adminAlertService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onInitiationRequested(WalletPawapayRefundInitiationEvent event) {
        try {
            issuer.initiate(event.itemId(), event.operationId())
                    .ifPresent(r -> settle(r.kind(), r.operationId(), false, r.failureCode()));
        } catch (RuntimeException e) {
            alert("initiation", event.operationId(), event.itemId(), e);
            throw e;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PawapayOperationCompletedEvent event) {
        if (!concernsWalletRefund(event.purpose(), event.kind())) {
            return;
        }
        try {
            settle(event.kind(), event.operationId(), true, null);
        } catch (RuntimeException e) {
            alert("completed", event.operationId(), null, e);
            throw e;
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(PawapayOperationFailedEvent event) {
        if (!concernsWalletRefund(event.purpose(), event.kind())) {
            return;
        }
        try {
            settle(event.kind(), event.operationId(), false, event.failureCode());
        } catch (RuntimeException e) {
            alert("failed", event.operationId(), null, e);
            throw e;
        }
    }

    private static boolean concernsWalletRefund(PawapayOperationPurpose purpose, PawapayOperationKind kind) {
        return purpose == PawapayOperationPurpose.WALLET_REFUND
                && (kind == PawapayOperationKind.REFUND || kind == PawapayOperationKind.PAYOUT);
    }

    /** Issue finale : verrou de la demande, puis de l'item, transition, résolution. */
    private void settle(PawapayOperationKind kind, UUID operationId, boolean completed, String failureCode) {
        Optional<UUID> requestId = itemRepository.findRefundRequestIdByPawapayOperationId(operationId);
        if (requestId.isEmpty()) {
            log.warn("Remboursement wallet pawaPay : aucun item lie a l'operation {} {}", kind, operationId);
            return;
        }
        WalletRefundRequestEntity request = refundRequestRepository.findByIdForUpdate(requestId.get()).orElse(null);
        if (request == null) {
            log.warn("Remboursement wallet pawaPay : demande {} de l'operation {} introuvable", requestId.get(),
                    operationId);
            return;
        }
        Optional<WalletRefundRequestItemEntity> item = issuer.findItem(kind, operationId);
        if (item.isEmpty()) {
            log.warn("Remboursement wallet pawaPay : aucun item lie a l'operation {} {}", kind, operationId);
            return;
        }
        issuer.applyPawapayOutcome(request, item.get(), kind, completed, failureCode);
        walletSelfRefundService.resolveIfComplete(request.getId());
    }

    private void alert(String stage, UUID operationId, UUID itemId, RuntimeException e) {
        adminAlertService.raise(ALERT_CODE,
                "Issue pawaPay (" + stage + ") d'un remboursement wallet non appliquee, operation " + operationId
                        + " : " + e.getMessage() + ". L'item reste PROCESSING ; verifier l'operation chez pawaPay "
                        + "et regler l'item manuellement.",
                Map.of("operationId", String.valueOf(operationId), "itemId", String.valueOf(itemId),
                        "stage", stage, "error", String.valueOf(e.getMessage())));
    }
}
