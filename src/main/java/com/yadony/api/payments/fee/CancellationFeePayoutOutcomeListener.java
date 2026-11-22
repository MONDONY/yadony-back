package com.yadony.api.payments.fee;

import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
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

import java.util.UUID;

/**
 * Issue pawaPay d'un versement de dédommagement d'annulation (purpose {@code CANCELLATION_FEE}, toujours un PAYOUT),
 * callback ou poller. L'événement ne porte pas la retenue : l'opération est relue pour son
 * {@code cancellation_fee_id}. Abouti : retenue {@code PAID_OUT} ; échoué : en attente, une alerte, le passage horaire
 * soumet un nouveau versement.
 *
 * <p>Écouteur à part, jamais une branche de {@code MobileMoneyPayoutOutcomeListener} : celui-ci ne traite que le
 * versement d'un colis livré (purpose {@code BID_PAYMENT}, {@code PaymentReleasedEvent}) et ignore déjà par sa garde de
 * purpose les opérations de ce paiement-ci, qui n'ont jamais de {@code payment_id}.
 *
 * <p>Règle 18 : {@code AFTER_COMMIT} + {@code REQUIRES_NEW}, les événements étant publiés dans la transaction qui
 * applique la transition ({@code PawapayOperationService#apply}). Aucun appel pawaPay ici. Enregistré dans
 * {@code PaymentListenerTransactionalContractTest#fullContractListeners()}.
 */
@Component
public class CancellationFeePayoutOutcomeListener {

    private static final Logger log = LoggerFactory.getLogger(CancellationFeePayoutOutcomeListener.class);

    private final PawapayOperationService operations;
    private final CancellationFeePayoutService payouts;

    public CancellationFeePayoutOutcomeListener(PawapayOperationService operations,
                                                CancellationFeePayoutService payouts) {
        this.operations = operations;
        this.payouts = payouts;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PawapayOperationCompletedEvent event) {
        UUID feeId = feeOf(event.purpose(), event.kind(), event.operationId());
        if (feeId != null) {
            payouts.mobileMoneyPayoutCompleted(feeId, event.operationId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(PawapayOperationFailedEvent event) {
        UUID feeId = feeOf(event.purpose(), event.kind(), event.operationId());
        if (feeId != null) {
            payouts.mobileMoneyPayoutFailed(feeId, PawapayText.clamp(event.failureCode()));
        }
    }

    /** Retenue versée par l'opération, ou {@code null} si l'opération n'est pas un versement de dédommagement. */
    private UUID feeOf(PawapayOperationPurpose purpose, PawapayOperationKind kind, UUID operationId) {
        if (purpose != PawapayOperationPurpose.CANCELLATION_FEE || kind != PawapayOperationKind.PAYOUT) {
            return null;
        }
        PawapayOperationEntity op = operations.get(operationId);
        if (op.getCancellationFeeId() == null) {
            log.error("Opération pawaPay {} CANCELLATION_FEE sans retenue", operationId);
        }
        return op.getCancellationFeeId();
    }
}
