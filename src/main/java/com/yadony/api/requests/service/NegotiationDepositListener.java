package com.yadony.api.requests.service;

import com.yadony.api.payments.events.MobileMoneyNegotiationDepositConfirmedEvent;
import com.yadony.api.payments.events.MobileMoneyNegotiationDepositFailedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Issues du dépôt mobile money d'un fil, publiées par {@code payments/} dans la transaction
 * du claim. Règle 18 : AFTER_COMMIT + REQUIRES_NEW, sinon on scellerait un fil sur un
 * séquestre pas encore durablement enregistré. Même découpage que
 * {@link NegotiationPaymentListener} pour la carte.
 */
@Component
public class NegotiationDepositListener {

    private final NegotiationService service;

    public NegotiationDepositListener(NegotiationService service) {
        this.service = service;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDepositConfirmed(MobileMoneyNegotiationDepositConfirmedEvent event) {
        service.finalizeAfterMobileMoneyDeposit(event.threadId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onDepositFailed(MobileMoneyNegotiationDepositFailedEvent event) {
        service.revertMobileMoneyDeposit(event.threadId(), "deposit-failed");
    }
}
