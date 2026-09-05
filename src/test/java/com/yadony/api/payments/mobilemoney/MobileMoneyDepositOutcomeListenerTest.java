package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MobileMoneyDepositOutcomeListenerTest {

    @Mock MobileMoneyBidPaymentService service;
    @Mock AdminAlertService adminAlert;
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
        verify(service, never()).confirmEscrow(any(), any());
        verify(service, never()).notifyDepositFailed(any(), any(), any());
    }

    @Test
    void failedDeposit_notifies() {
        UUID op = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        listener.onFailed(new PawapayOperationFailedEvent(op, PawapayOperationKind.DEPOSIT, payment, "PAYMENT_NOT_APPROVED", "no"));
        verify(service).notifyDepositFailed(op, payment, "PAYMENT_NOT_APPROVED");
    }

    /**
     * Ronde 1, point 4 (Important) : un échec réseau du remboursement automatique
     * (deposit encaissé après annulation) survient APRÈS le point irréversible —
     * l'opération pawaPay est déjà COMPLETED, jamais republiée, jamais relue par le
     * poller (qui ne balaie que les opérations ouvertes). Sans alerte, l'argent reste
     * bloqué sans qu'aucun humain ne le sache. {@code confirmEscrow} propage
     * l'exception (comportement inchangé, la transaction doit toujours être annulée) ;
     * l'écouteur doit intercepter, alerter, PUIS repropager à l'identique — jamais
     * avaler l'erreur (qui commiterait silencieusement un état partiel).
     */
    @Test
    void completedDeposit_confirmEscrowThrows_alertsAdminThenRethrows() {
        UUID op = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        RuntimeException boom = new IllegalStateException("pawaPay indisponible");
        doThrow(boom).when(service).confirmEscrow(op, payment);

        assertThatThrownBy(() -> listener.onCompleted(new PawapayOperationCompletedEvent(op, PawapayOperationKind.DEPOSIT, payment)))
                .isSameAs(boom);

        verify(adminAlert).raise(any(), any(), any());
    }

    /** Symétrique du test précédent : pas d'exception, pas d'alerte. */
    @Test
    void completedDeposit_confirmEscrowSucceeds_neverAlerts() {
        UUID op = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        listener.onCompleted(new PawapayOperationCompletedEvent(op, PawapayOperationKind.DEPOSIT, payment));
        verify(adminAlert, never()).raise(any(), any(), any());
    }
}
