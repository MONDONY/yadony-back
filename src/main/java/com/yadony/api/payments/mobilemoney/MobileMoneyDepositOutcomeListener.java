package com.yadony.api.payments.mobilemoney;

import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Tout premiers écouteurs des événements pawaPay génériques {@link PawapayOperationCompletedEvent}
 * / {@link PawapayOperationFailedEvent} : traduit une transition d'opération en effet
 * métier pour le rail mobile money (passage en séquestre, notification d'échec).
 *
 * <p><b>Règle 18 du projet, non négociable</b> — {@code @TransactionalEventListener(phase =
 * AFTER_COMMIT)} combiné à {@code @Transactional(propagation = REQUIRES_NEW)} sur les deux
 * méthodes, jamais un {@code @EventListener} seul. Les deux événements sont publiés À
 * L'INTÉRIEUR de la transaction qui applique la transition
 * ({@code PawapayOperationService#apply}, voir le Javadoc des deux événements). Un
 * écouteur simple s'exécuterait avant le commit de cette transaction — donc sur une
 * transition pas encore visible des autres connexions, et même si cette transaction
 * était ensuite annulée — et confirmerait un séquestre pour un deposit qui n'a jamais été
 * durablement enregistré.
 */
@Component
public class MobileMoneyDepositOutcomeListener {

    private final MobileMoneyBidPaymentService service;

    public MobileMoneyDepositOutcomeListener(MobileMoneyBidPaymentService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCompleted(PawapayOperationCompletedEvent event) {
        if (event.kind() != PawapayOperationKind.DEPOSIT || event.paymentId() == null) return;
        service.confirmEscrow(event.operationId(), event.paymentId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onFailed(PawapayOperationFailedEvent event) {
        if (event.kind() != PawapayOperationKind.DEPOSIT || event.paymentId() == null) return;
        service.notifyDepositFailed(event.operationId(), event.paymentId(), event.failureCode());
    }
}
