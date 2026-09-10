package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MobileMoneyDepositOutcomeListenerTest {

    @Mock MobileMoneyBidPaymentService bidService;
    @Mock MobileMoneyNegotiationPaymentService negotiationService;
    @Mock PaymentRepository paymentRepository;
    @Mock AdminAlertService adminAlert;
    @InjectMocks MobileMoneyDepositOutcomeListener listener;

    private PawapayOperationCompletedEvent depositCompleted(UUID paymentId) {
        return new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, paymentId);
    }

    private PawapayOperationFailedEvent depositFailed(UUID paymentId, String failureCode) {
        return new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, paymentId, failureCode, "no");
    }

    // ── Aiguillage bid / fil ───────────────────────────────────────────────

    @Test
    void depositOnThreadPayment_goesToNegotiationService() {
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = new PaymentEntity();
        p.setNegotiationThreadId(UUID.randomUUID());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        listener.onCompleted(depositCompleted(paymentId));

        verify(negotiationService).confirmEscrow(any(), any());
        verify(bidService, never()).confirmEscrow(any(), any());
    }

    @Test
    void depositOnBidPayment_goesToBidService() {
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        listener.onCompleted(depositCompleted(paymentId));

        verify(bidService).confirmEscrow(any(), any());
        verify(negotiationService, never()).confirmEscrow(any(), any());
    }

    @Test
    void failedDepositOnThreadPayment_goesToNegotiationService() {
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = new PaymentEntity();
        p.setNegotiationThreadId(UUID.randomUUID());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        listener.onFailed(depositFailed(paymentId, "PAYER_LIMIT_REACHED"));

        verify(negotiationService).notifyDepositFailed(any(), any(), any());
        verify(bidService, never()).notifyDepositFailed(any(), any(), any());
    }

    @Test
    void failedDepositOnBidPayment_goesToBidService() {
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        listener.onFailed(depositFailed(paymentId, "PAYER_LIMIT_REACHED"));

        verify(bidService).notifyDepositFailed(any(), any(), any());
        verify(negotiationService, never()).notifyDepositFailed(any(), any(), any());
    }

    // ── Filtres kind / paymentId ─────────────────────────────────────────

    @Test
    void otherKinds_orNoPayment_areIgnored() {
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.PAYOUT, UUID.randomUUID()));
        listener.onCompleted(new PawapayOperationCompletedEvent(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, null));
        listener.onFailed(new PawapayOperationFailedEvent(UUID.randomUUID(), PawapayOperationKind.REFUND, UUID.randomUUID(), "X", "y"));
        verify(bidService, never()).confirmEscrow(any(), any());
        verify(negotiationService, never()).confirmEscrow(any(), any());
        verify(bidService, never()).notifyDepositFailed(any(), any(), any());
        verify(negotiationService, never()).notifyDepositFailed(any(), any(), any());
    }

    // ── Filet de sécurité : alerte + repropagation ──────────────────────────

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
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        RuntimeException boom = new IllegalStateException("pawaPay indisponible");
        doThrow(boom).when(bidService).confirmEscrow(any(), any());

        assertThatThrownBy(() -> listener.onCompleted(depositCompleted(paymentId))).isSameAs(boom);

        verify(adminAlert).raise(any(), any(), any());
    }

    /** Symétrique du test précédent : pas d'exception, pas d'alerte. */
    @Test
    void completedDeposit_confirmEscrowSucceeds_neverAlerts() {
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        listener.onCompleted(depositCompleted(paymentId));

        verify(adminAlert, never()).raise(any(), any(), any());
    }
}
