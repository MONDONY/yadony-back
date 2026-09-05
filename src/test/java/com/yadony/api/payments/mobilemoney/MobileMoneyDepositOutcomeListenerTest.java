package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@ExtendWith(MockitoExtension.class)
class MobileMoneyDepositOutcomeListenerTest {

    @Mock MobileMoneyBidPaymentService service;
    @InjectMocks MobileMoneyDepositOutcomeListener listener;

    @Test
    void completedDeposit_confirmsEscrow() {
        UUID op = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        listener.onCompleted(new PawapayOperationCompletedEvent(op, PawapayOperationKind.DEPOSIT, payment));
        verify(service).confirmEscrow(op, payment);
    }

    @Test
    void otherKinds_orNoPayment_areIgnored() {
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT, UUID.randomUUID()));
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, null));
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.REFUND, UUID.randomUUID(), "X", "y"));
        verify(service, never()).confirmEscrow(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(service, never()).notifyDepositFailed(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void failedDeposit_notifies() {
        UUID op = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        listener.onFailed(new PawapayOperationFailedEvent(op, PawapayOperationKind.DEPOSIT, payment, "PAYMENT_NOT_APPROVED", "no"));
        verify(service).notifyDepositFailed(op, payment, "PAYMENT_NOT_APPROVED");
    }

    /**
     * Preuve exigée par la tâche 14 (au-delà du cahier des charges) : les deux écouteurs des
     * tout premiers événements pawaPay portent bien les deux annotations non négociables —
     * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} ET
     * {@code @Transactional(propagation = REQUIRES_NEW)}. Sans ce test réflexif, un futur
     * retrait accidentel de l'une des deux annotations resterait vert : les trois tests
     * ci-dessus appellent {@code onCompleted}/{@code onFailed} directement, en dehors de tout
     * conteneur Spring, donc sans jamais exercer ces annotations.
     */
    @Test
    void bothListeners_useAfterCommitTransactionalEventListener_andRequiresNewTransaction() throws NoSuchMethodException {
        Method onCompleted = MobileMoneyDepositOutcomeListener.class
                .getMethod("onCompleted", PawapayOperationCompletedEvent.class);
        Method onFailed = MobileMoneyDepositOutcomeListener.class
                .getMethod("onFailed", PawapayOperationFailedEvent.class);

        for (Method method : new Method[] {onCompleted, onFailed}) {
            TransactionalEventListener txListener = method.getAnnotation(TransactionalEventListener.class);
            Transactional tx = method.getAnnotation(Transactional.class);
            assertThat(txListener).as(method.getName() + " : @TransactionalEventListener").isNotNull();
            assertThat(txListener.phase()).as(method.getName() + " : phase AFTER_COMMIT").isEqualTo(TransactionPhase.AFTER_COMMIT);
            assertThat(tx).as(method.getName() + " : @Transactional").isNotNull();
            assertThat(tx.propagation()).as(method.getName() + " : propagation REQUIRES_NEW").isEqualTo(Propagation.REQUIRES_NEW);
        }
    }
}
