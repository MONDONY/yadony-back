package com.yadony.api.payments;

import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.mobilemoney.MobileMoneyNegotiationPaymentService;
import com.yadony.api.requests.NegotiationMobileMoneyPort;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentServiceCancelNegotiationEscrowTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PaymentService service = PaymentServiceTestFactory.bare(
            paymentRepository, mock(UserRepository.class), auditService, mock(AdminAlertService.class));

    private final UUID THREAD = UUID.randomUUID();
    private static final String REASON = "negotiation-cancelled";

    private PaymentEntity payment(PaymentStatus status, String piId) {
        PaymentEntity p = new PaymentEntity();
        p.setNegotiationThreadId(THREAD);
        p.setStripePaymentIntentId(piId);
        p.setStatus(status);
        PaymentServiceTestFactory.setId(p, UUID.randomUUID());
        return p;
    }

    @Test
    @DisplayName("escrow ESCROW (held) → annule le PI, passe CANCELLED, audit, true")
    void cancel_escrow_cancelsAndReturnsTrue() throws StripeException {
        PaymentEntity p = payment(PaymentStatus.ESCROW, "pi_hold");
        when(paymentRepository.findByNegotiationThreadId(THREAD)).thenReturn(Optional.of(p));
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_hold")).thenReturn(pi);

            assertThat(service.cancelNegotiationEscrow(THREAD, REASON)).isTrue();

            verify(pi).cancel();
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            verify(paymentRepository).save(p);
            verify(auditService).log(eq("PAYMENT"), eq(p.getId()),
                eq("NEGOTIATION_ESCROW_CANCELED"), any(), any());
        }
    }

    @Test
    @DisplayName("escrow PENDING → annule, CANCELLED, true")
    void cancel_pending_cancelsAndReturnsTrue() throws StripeException {
        PaymentEntity p = payment(PaymentStatus.PENDING, "pi_pending");
        when(paymentRepository.findByNegotiationThreadId(THREAD)).thenReturn(Optional.of(p));
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_pending")).thenReturn(pi);

            assertThat(service.cancelNegotiationEscrow(THREAD, REASON)).isTrue();

            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            verify(paymentRepository).save(p);
        }
    }

    @Test
    @DisplayName("aucun escrow pour le thread → true, aucun appel Stripe")
    void cancel_noPayment_returnsTrue() {
        when(paymentRepository.findByNegotiationThreadId(THREAD)).thenReturn(Optional.empty());
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            assertThat(service.cancelNegotiationEscrow(THREAD, REASON)).isTrue();
            mocked.verifyNoInteractions();
        }
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("escrow terminal (RELEASED) → true, pas d'annulation")
    void cancel_terminal_returnsTrue() {
        PaymentEntity p = payment(PaymentStatus.RELEASED, "pi_done");
        when(paymentRepository.findByNegotiationThreadId(THREAD)).thenReturn(Optional.of(p));
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            assertThat(service.cancelNegotiationEscrow(THREAD, REASON)).isTrue();
            mocked.verifyNoInteractions();
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("Stripe refuse l'annulation → false, statut inchangé, pas de save")
    void cancel_stripeError_returnsFalse() throws StripeException {
        PaymentEntity p = payment(PaymentStatus.ESCROW, "pi_boom");
        when(paymentRepository.findByNegotiationThreadId(THREAD)).thenReturn(Optional.of(p));
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenThrow(mock(StripeException.class));
            mocked.when(() -> PaymentIntent.retrieve("pi_boom")).thenReturn(pi);

            assertThat(service.cancelNegotiationEscrow(THREAD, REASON)).isFalse();
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.ESCROW);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("threadId null → true, pas d'accès DB")
    void cancel_nullThread_returnsTrue() {
        assertThat(service.cancelNegotiationEscrow(null, REASON)).isTrue();
        verifyNoInteractions(paymentRepository);
    }

    @Test
    @DisplayName("PI déjà 'canceled' (rollback antérieur) → idempotent : pas de cancel(), entité CANCELLED, true")
    void cancel_alreadyCanceledPi_idempotentTrue() throws StripeException {
        PaymentEntity p = payment(PaymentStatus.ESCROW, "pi_already");
        when(paymentRepository.findByNegotiationThreadId(THREAD)).thenReturn(Optional.of(p));
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("canceled");
            mocked.when(() -> PaymentIntent.retrieve("pi_already")).thenReturn(pi);

            assertThat(service.cancelNegotiationEscrow(THREAD, REASON)).isTrue();

            verify(pi, never()).cancel();
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            verify(paymentRepository).save(p);
        }
    }

    @Test
    @DisplayName("rail PAWAPAY, PENDING → libéré via le service mobile money, jamais Stripe")
    void cancel_pawapayPending_releasesViaMobileMoneyService_neverCallsStripe() {
        MobileMoneyNegotiationPaymentService mobileMoneyService = mock(MobileMoneyNegotiationPaymentService.class);
        ReflectionTestUtils.setField(service, "mobileMoneyNegotiationPaymentService", mobileMoneyService);
        PaymentEntity p = payment(PaymentStatus.PENDING, null);
        p.setRail(PaymentRail.PAWAPAY);
        when(paymentRepository.findByNegotiationThreadId(THREAD)).thenReturn(Optional.of(p));
        when(mobileMoneyService.releasePendingDeposit(THREAD))
                .thenReturn(NegotiationMobileMoneyPort.ReleaseOutcome.CANCELLED);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            assertThat(service.cancelNegotiationEscrow(THREAD, REASON)).isTrue();
            mocked.verifyNoInteractions();
        }
        verify(mobileMoneyService).releasePendingDeposit(THREAD);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("rail PAWAPAY, ESCROW → remboursé via le service mobile money")
    void cancel_pawapayEscrowed_refunds() {
        MobileMoneyNegotiationPaymentService mobileMoneyService = mock(MobileMoneyNegotiationPaymentService.class);
        ReflectionTestUtils.setField(service, "mobileMoneyNegotiationPaymentService", mobileMoneyService);
        PaymentEntity p = payment(PaymentStatus.ESCROW, null);
        p.setRail(PaymentRail.PAWAPAY);
        when(paymentRepository.findByNegotiationThreadId(THREAD)).thenReturn(Optional.of(p));
        when(mobileMoneyService.refundEscrowedDeposit(THREAD)).thenReturn(true);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            assertThat(service.cancelNegotiationEscrow(THREAD, REASON)).isTrue();
            mocked.verifyNoInteractions();
        }
        verify(mobileMoneyService).refundEscrowedDeposit(THREAD);
        verify(paymentRepository, never()).save(any());
    }
}
