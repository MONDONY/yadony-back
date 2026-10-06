package com.yadony.api.payments;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NegotiationEscrowAdapterTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentService paymentService = mock(PaymentService.class);
    private final NegotiationEscrowAdapter adapter =
        new NegotiationEscrowAdapter(paymentRepository, paymentService);

    private final UUID THREAD = UUID.randomUUID();

    private PaymentEntity paymentWithPi(String piId) {
        PaymentEntity p = new PaymentEntity();
        p.setNegotiationThreadId(THREAD);
        p.setStripePaymentIntentId(piId);
        p.setAmount(new java.math.BigDecimal("35.00")); // gross → 3500 cents
        return p;
    }

    @Test
    @DisplayName("PI authentique du thread en requires_capture + montant cohérent → true")
    void verify_realAuthorizedEscrow_returnsTrue() throws StripeException {
        when(paymentRepository.findByNegotiationThreadId(THREAD))
            .thenReturn(Optional.of(paymentWithPi("pi_real")));
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            when(pi.getAmount()).thenReturn(3500L);
            when(pi.getLatestCharge()).thenReturn("ch_real");
            mocked.when(() -> PaymentIntent.retrieve("pi_real")).thenReturn(pi);

            assertThat(adapter.verifyNegotiationEscrow(THREAD, "pi_real")).isTrue();

            // Sentry/INV-08 : le paiement passe en séquestre tout de suite, sans attendre un
            // webhook qui n'arrive pas toujours (aucun reçu en staging).
            verify(paymentService).promoteNegotiationEscrowIfPending(THREAD, "pi_real", "ch_real");
        }
    }

    @Test
    @DisplayName("PI requires_capture mais montant ≠ prix convenu → false (defense-in-depth)")
    void verify_amountMismatch_returnsFalse() throws StripeException {
        when(paymentRepository.findByNegotiationThreadId(THREAD))
            .thenReturn(Optional.of(paymentWithPi("pi_cheap")));
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            when(pi.getAmount()).thenReturn(100L); // 1,00 € au lieu de 35,00 €
            mocked.when(() -> PaymentIntent.retrieve("pi_cheap")).thenReturn(pi);

            assertThat(adapter.verifyNegotiationEscrow(THREAD, "pi_cheap")).isFalse();
            verify(paymentService, never()).promoteNegotiationEscrowIfPending(any(), any(), any());
        }
    }

    @Test
    @DisplayName("PI id forgé ≠ PI server du thread → false, sans appeler Stripe")
    void verify_forgedPaymentIntentId_returnsFalse() {
        when(paymentRepository.findByNegotiationThreadId(THREAD))
            .thenReturn(Optional.of(paymentWithPi("pi_real")));
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            assertThat(adapter.verifyNegotiationEscrow(THREAD, "x")).isFalse();
            mocked.verifyNoInteractions();
            verify(paymentService, never()).promoteNegotiationEscrowIfPending(any(), any(), any());
        }
    }

    @Test
    @DisplayName("aucun escrow créé pour le thread → false")
    void verify_noPaymentForThread_returnsFalse() {
        when(paymentRepository.findByNegotiationThreadId(THREAD)).thenReturn(Optional.empty());
        assertThat(adapter.verifyNegotiationEscrow(THREAD, "pi_real")).isFalse();
    }

    @Test
    @DisplayName("PI correct mais carte jamais autorisée (requires_payment_method) → false")
    void verify_paymentIntentNotAuthorized_returnsFalse() throws StripeException {
        when(paymentRepository.findByNegotiationThreadId(THREAD))
            .thenReturn(Optional.of(paymentWithPi("pi_pending")));
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_payment_method");
            mocked.when(() -> PaymentIntent.retrieve("pi_pending")).thenReturn(pi);

            assertThat(adapter.verifyNegotiationEscrow(THREAD, "pi_pending")).isFalse();
            verify(paymentService, never()).promoteNegotiationEscrowIfPending(any(), any(), any());
        }
    }

    @Test
    @DisplayName("erreur Stripe → false (fail closed)")
    void verify_stripeError_returnsFalse() throws StripeException {
        when(paymentRepository.findByNegotiationThreadId(THREAD))
            .thenReturn(Optional.of(paymentWithPi("pi_boom")));
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.retrieve("pi_boom"))
                .thenThrow(mock(StripeException.class));

            assertThat(adapter.verifyNegotiationEscrow(THREAD, "pi_boom")).isFalse();
        }
    }

    @Test
    @DisplayName("threadId ou paymentIntentId null/blank → false, sans toucher la DB")
    void verify_blankInputs_returnsFalse() {
        assertThat(adapter.verifyNegotiationEscrow(THREAD, "  ")).isFalse();
        assertThat(adapter.verifyNegotiationEscrow(THREAD, null)).isFalse();
        assertThat(adapter.verifyNegotiationEscrow(null, "pi_real")).isFalse();
        verifyNoInteractions(paymentRepository);
    }

    @Test
    @DisplayName("releaseEscrowForMethodSwitch — délègue à PaymentService.cancelNegotiationEscrow (succès → true)")
    void release_delegatesToPaymentService_true() {
        when(paymentService.cancelNegotiationEscrow(THREAD, "payment-method-switch")).thenReturn(true);
        assertThat(adapter.releaseEscrowForMethodSwitch(THREAD)).isTrue();
        verify(paymentService).cancelNegotiationEscrow(THREAD, "payment-method-switch");
    }

    @Test
    @DisplayName("releaseEscrowForMethodSwitch — échec de release → false (propagé)")
    void release_delegatesToPaymentService_false() {
        when(paymentService.cancelNegotiationEscrow(THREAD, "payment-method-switch")).thenReturn(false);
        assertThat(adapter.releaseEscrowForMethodSwitch(THREAD)).isFalse();
    }
}
