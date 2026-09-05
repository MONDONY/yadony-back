package com.yadony.api.payments;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.stripe.exception.ApiException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCancelParams;
import com.stripe.param.RefundCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundProcessorTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;
    @Mock private AdminAlertService adminAlert;
    // Ronde 1, point 10 : RefundProcessor prend désormais 7 paramètres constructeur (rail
    // mobile money, tâche 17) — ces 4 mocks supplémentaires ne sont jamais exercés par les
    // tests de cette classe (chemin Stripe uniquement, chaque paiement est de rail STRIPE).
    @Mock private com.yadony.api.payments.pawapay.PawapayOperationService pawapayOperations;
    @Mock private com.yadony.api.payments.pawapay.PawapaySubmissionService pawapaySubmission;
    @Mock private com.yadony.api.admin.AdminAlertRepository alertRepository;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private RefundProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RefundProcessor(paymentRepository, auditService, adminAlert,
                pawapayOperations, pawapaySubmission, alertRepository, transactionManager);
    }

    private PaymentEntity payment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(UUID.randomUUID());
        p.setStripePaymentIntentId("pi_xxx");
        p.setStatus(status);
        p.setAmount(new BigDecimal("30.00"));
        p.setCommissionAmount(new BigDecimal("3.60"));
        return p;
    }

    @Test
    void escrow_captured_payment_is_refunded_with_idempotency_key() {
        // PI capturé (succeeded) → vrai remboursement via Refund.create.
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            ArgumentCaptor<RequestOptions> optsCaptor = ArgumentCaptor.forClass(RequestOptions.class);
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), optsCaptor.capture()))
                    .thenReturn(mock(Refund.class));

            boolean refunded = processor.processRefund(paymentId, "PAYMENT_REFUNDED_TEST",
                    p.getBidId(), Map.of("reason", "test"));

            assertThat(refunded).isTrue();
            assertThat(optsCaptor.getValue().getIdempotencyKey())
                    .isEqualTo("refund-" + paymentId);
        }
        verify(paymentRepository).markRefundedIfEscrow(paymentId);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED_TEST"), any(), any(Map.class));
    }

    @Test
    void escrow_uncaptured_payment_is_cancelled_not_refunded() throws Exception {
        // Cas normal chez Yadony : PI autorisé non capturé (requires_capture) →
        // l'autorisation est annulée (pi.cancel), JAMAIS Refund.create (que Stripe
        // refuse sur une charge non capturée).
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            when(pi.cancel(any(PaymentIntentCancelParams.class))).thenReturn(pi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            boolean handled = processor.processRefund(paymentId, "PAYMENT_REFUNDED_TEST",
                    p.getBidId(), Map.of("reason", "test"));

            assertThat(handled).isTrue();
            verify(pi).cancel(any(PaymentIntentCancelParams.class));
            refundStatic.verifyNoInteractions();
        }
        verify(paymentRepository).markRefundedIfEscrow(paymentId);
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED_TEST"), any(), any(Map.class));
    }

    @Test
    void escrow_already_canceled_pi_is_noop_stripe_call() throws Exception {
        // PI déjà 'canceled' (rollback antérieur) → ni cancel ni refund, idempotent.
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("canceled");
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            boolean handled = processor.processRefund(paymentId, "PAYMENT_REFUNDED_TEST",
                    p.getBidId(), Map.of("reason", "test"));

            assertThat(handled).isTrue();
            verify(pi, never()).cancel(any(PaymentIntentCancelParams.class));
            refundStatic.verifyNoInteractions();
        }
        verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_REFUNDED_TEST"), any(), any(Map.class));
    }

    @Test
    void escrow_refund_skipped_when_claim_lost() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(0);

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            boolean refunded = processor.processRefund(paymentId, "X", null, Map.of());
            assertThat(refunded).isFalse();
            refundStatic.verifyNoInteractions();
        }
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void escrow_refund_stripe_failure_raises_alert_and_throws() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class), any(RequestOptions.class)))
                    .thenThrow(new ApiException("boom", null, null, 500, null));

            assertThatThrownBy(() -> processor.processRefund(paymentId, "X", null, Map.of()))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(adminAlert).raise(eq("STRIPE_REFUND_FAILED"), any(), any(Map.class));
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void pending_payment_is_cancelled_not_refunded() throws Exception {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel(any(PaymentIntentCancelParams.class))).thenReturn(pi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            boolean handled = processor.processRefund(paymentId, "PAYMENT_CANCELLED_TEST",
                    p.getBidId(), Map.of());

            assertThat(handled).isTrue();
            verify(pi).cancel(any(PaymentIntentCancelParams.class));
            refundStatic.verifyNoInteractions();
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(paymentRepository).save(p);
    }

    @Test
    void released_payment_is_never_refunded() {
        PaymentEntity p = payment(PaymentStatus.RELEASED);
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        try (MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            boolean handled = processor.processRefund(paymentId, "X", null, Map.of());
            assertThat(handled).isFalse();
            refundStatic.verifyNoInteractions();
        }
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void missing_payment_is_noop() {
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());
        assertThat(processor.processRefund(paymentId, "X", null, Map.of())).isFalse();
    }
}
