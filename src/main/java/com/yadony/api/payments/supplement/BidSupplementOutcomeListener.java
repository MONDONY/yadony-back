package com.yadony.api.payments.supplement;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayText;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Issue des opérations pawaPay d'un supplément de prix (purpose {@code BID_SUPPLEMENT}, plan §4.3
 * « Confirmation »), callback ou poller. L'événement ne porte pas l'identifiant du supplément :
 * l'opération est relue. Les écouteurs du paiement principal ignorent ces opérations (garde de
 * purpose).
 * <ul>
 *   <li>dépôt abouti : {@code PAID} (paiement tardif sur une demande close : remboursé) ;</li>
 *   <li>dépôt échoué : la demande reste {@code PAYMENT_PENDING}, l'expéditeur peut relancer
 *       jusqu'à l'échéance ;</li>
 *   <li>versement abouti : {@code PAID_OUT} ; échoué : retour à {@code PAID}, alerte, le filet réessaie ;</li>
 *   <li>remboursement abouti : {@code REFUNDED} ; échoué : alerte, le filet réessaie.</li>
 * </ul>
 *
 * <p>Règle 18 : {@code AFTER_COMMIT} + {@code REQUIRES_NEW}, les événements étant publiés dans la
 * transaction qui applique la transition. Aucun appel pawaPay ici : un remboursement à lancer
 * (paiement tardif) passe par {@code BidSupplementRefundRequestedEvent}, après le commit.
 */
@Component
public class BidSupplementOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(BidSupplementOutcomeListener.class);

    private final PawapayOperationService operations;
    private final BidSupplementMobileMoneyLedger ledger;
    private final AdminAlertEscalator alerts;

    public BidSupplementOutcomeListener(PawapayOperationService operations, BidSupplementMobileMoneyLedger ledger,
                                        AdminAlertEscalator alerts) {
        this.operations = operations;
        this.ledger = ledger;
        this.alerts = alerts;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PawapayOperationCompletedEvent event) {
        if (event.purpose() != PawapayOperationPurpose.BID_SUPPLEMENT) {
            return;
        }
        PawapayOperationEntity op = operations.get(event.operationId());
        UUID supplementId = op.getBidSupplementId();
        if (supplementId == null) {
            log.error("Opération pawaPay {} BID_SUPPLEMENT sans supplément", op.getId());
            return;
        }
        switch (op.getKind()) {
            case DEPOSIT -> ledger.markPaid(supplementId, op.getId(), "pawapay");
            case PAYOUT -> ledger.completePayout(supplementId, op.getId());
            case REFUND -> ledger.completeRefund(supplementId, op.getId(), "pawapay");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(PawapayOperationFailedEvent event) {
        if (event.purpose() != PawapayOperationPurpose.BID_SUPPLEMENT) {
            return;
        }
        PawapayOperationEntity op = operations.get(event.operationId());
        UUID supplementId = op.getBidSupplementId();
        if (supplementId == null) {
            log.error("Opération pawaPay {} BID_SUPPLEMENT sans supplément", op.getId());
            return;
        }
        String failureCode = PawapayText.clamp(event.failureCode());
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("operationId", op.getId().toString());
        extra.put("failureCode", String.valueOf(failureCode));
        switch (op.getKind()) {
            case DEPOSIT -> ledger.auditOnly(supplementId, "BID_SUPPLEMENT_MM_DEPOSIT_FAILED", extra);
            case PAYOUT -> {
                ledger.revertPayout(supplementId, failureCode);
                alerts.raiseOnce("SUPP_MM_PAYOUT_FAILED_" + supplementId,
                        "Versement mobile money du supplément " + supplementId + " échoué : " + failureCode,
                        context(supplementId, extra));
            }
            case REFUND -> {
                ledger.auditOnly(supplementId, "BID_SUPPLEMENT_REFUND_FAILED", extra);
                alerts.raiseOnce("SUPP_REFUND_FAILED_" + supplementId,
                        "Remboursement mobile money du supplément " + supplementId + " échoué : " + failureCode,
                        context(supplementId, extra));
            }
        }
    }

    private static Map<String, Object> context(UUID supplementId, Map<String, Object> extra) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("supplementId", supplementId.toString());
        context.putAll(extra);
        return context;
    }
}
