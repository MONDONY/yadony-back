package com.yadony.api.payments.mobilemoney;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayText;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.util.Map;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Écoute {@link PawapayOperationCompletedEvent} / {@link PawapayOperationFailedEvent} pour le
 * seul {@code kind == REFUND} — jumeau, côté remboursement, de
 * {@link MobileMoneyPayoutOutcomeListener} (payout, tâche 16) et
 * {@link MobileMoneyDepositOutcomeListener} (deposit, tâche 14).
 *
 * <p>La décision métier (claim {@code ESCROW → REFUNDED}, choix du deposit à rembourser) est
 * déjà prise par {@link com.yadony.api.payments.RefundProcessor#processRefund} AVANT que le
 * refund pawaPay ne soit soumis : ce listener se contente de tracer l'aboutissement réel chez
 * pawaPay.
 *
 * <p>{@code COMPLETED} : l'argent est réellement revenu à l'expéditeur, simple entrée d'audit
 * (le paiement est déjà {@code REFUNDED} depuis le claim, aucune autre action déclenchée).
 *
 * <p>{@code FAILED} : l'argent n'est PAS revenu bien que le paiement soit déjà {@code REFUNDED}
 * en base — {@code RefundProcessor} a validé le claim au moment de la soumission, il n'y a pas
 * de retour automatique en {@code ESCROW} ici (la décision REFUNDED a déjà été auditée et
 * communiquée) : alerte admin pour reprise manuelle (relance, tâche 18).
 *
 * <p><b>Règle 18 du projet, non négociable</b> — {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} combiné à {@code @Transactional(propagation = REQUIRES_NEW)} sur les deux
 * méthodes, jamais un {@code @EventListener} seul : les deux événements sont publiés À
 * L'INTÉRIEUR de la transaction qui applique la transition
 * ({@code PawapayOperationService#apply}, voir le Javadoc des deux événements). Les deux
 * méthodes sont enregistrées dans
 * {@code PaymentListenerTransactionalContractTest#fullContractListeners()}.
 */
@Component
public class MobileMoneyRefundOutcomeListener {

    private final AdminAlertService adminAlert;
    private final AuditService audit;

    public MobileMoneyRefundOutcomeListener(AdminAlertService adminAlert, AuditService audit) {
        this.adminAlert = adminAlert;
        this.audit = audit;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PawapayOperationCompletedEvent event) {
        if (event.kind() != PawapayOperationKind.REFUND || event.paymentId() == null) return;
        audit.log("PAYMENT", event.paymentId(), "MM_REFUND_COMPLETED", null,
                Map.of("operationId", event.operationId().toString()));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(PawapayOperationFailedEvent event) {
        if (event.kind() != PawapayOperationKind.REFUND || event.paymentId() == null) return;
        String failureCode = PawapayText.clamp(event.failureCode());
        String failureMessage = PawapayText.clamp(event.failureMessage());
        audit.log("PAYMENT", event.paymentId(), "MM_REFUND_FAILED", null,
                Map.of("operationId", event.operationId().toString(), "failureCode", String.valueOf(failureCode)));
        adminAlert.raise("PAWAPAY_REFUND_FAILED",
                "Remboursement mobile money échoué pour le paiement " + event.paymentId() + " : " + failureCode,
                Map.of("paymentId", event.paymentId().toString(), "operationId", event.operationId().toString(),
                        "failureCode", String.valueOf(failureCode), "failureMessage", String.valueOf(failureMessage)));
    }
}
