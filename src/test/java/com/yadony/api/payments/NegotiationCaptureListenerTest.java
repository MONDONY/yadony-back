package com.yadony.api.payments;

import com.yadony.api.payments.events.PaymentEscrowReadyEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Le listener décide QUAND capturer (paiement de négociation passé ESCROW) ; la capture
 * elle-même (garde, clé d'idempotence, montant, audit, alerte) est testée dans
 * {@link EscrowCaptureServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class NegotiationCaptureListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private EscrowCaptureService escrowCapture;

    private NegotiationCaptureListener listener;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new NegotiationCaptureListener(paymentRepository, escrowCapture);
    }

    private PaymentEscrowReadyEvent event() {
        // Negotiation escrow-ready event carries a null bidId (payment keyed on the thread).
        return new PaymentEscrowReadyEvent(null, paymentId);
    }

    private PaymentEntity threadPayment(PaymentStatus status, boolean legacy) {
        PaymentEntity p = new PaymentEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", paymentId);
        p.setNegotiationThreadId(threadId);
        p.setStripePaymentIntentId("pi_xxx");
        p.setStatus(status);
        p.setLegacyDestinationCharge(legacy);
        p.setAmount(new BigDecimal("64.50"));
        p.setCommissionAmount(new BigDecimal("6.91"));
        // bidId left null on purpose — negotiation/thread payment
        return p;
    }

    @Test
    void captures_negotiation_escrow_through_the_shared_service() {
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(threadPayment(PaymentStatus.ESCROW, false)));
        when(escrowCapture.ensureCaptured(paymentId, "negotiation-escrow-ready"))
                .thenReturn(new EscrowCaptureService.Outcome("ch_1", true));

        listener.onEscrowReady(event());

        verify(escrowCapture).ensureCaptured(paymentId, "negotiation-escrow-ready");
    }

    @Test
    void capture_failure_is_logged_not_thrown() {
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(threadPayment(PaymentStatus.ESCROW, false)));
        when(escrowCapture.ensureCaptured(any(), any()))
                .thenThrow(new EscrowCaptureService.EscrowCaptureException("refus", "requires_capture", null));

        assertThatNoException().isThrownBy(() -> listener.onEscrowReady(event()));
    }

    @Test
    void skips_classic_bid_payment() {
        // Bid payments emit the same event but are captured by BidAcceptedEventListener.
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false);
        p.setNegotiationThreadId(null);
        p.setBidId(UUID.randomUUID());
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));

        listener.onEscrowReady(event());

        verifyNoInteractions(escrowCapture);
    }

    @Test
    void no_op_when_payment_not_found() {
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());
        assertThatNoException().isThrownBy(() -> listener.onEscrowReady(event()));
        verifyNoInteractions(escrowCapture);
    }

    @Test
    void skips_legacy_payment() {
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(threadPayment(PaymentStatus.ESCROW, true)));
        listener.onEscrowReady(event());
        verifyNoInteractions(escrowCapture);
    }

    @Test
    void skips_when_payment_not_in_escrow() {
        // Defensive: if the status isn't ESCROW (e.g. already RELEASED), do nothing.
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(threadPayment(PaymentStatus.RELEASED, false)));
        listener.onEscrowReady(event());
        verifyNoInteractions(escrowCapture);
    }
}
