package com.yadony.api.payments;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contrat de délégation : sur un sender no-show confirmé portant un escrow Stripe,
 * le listener délègue le remboursement à {@link RefundProcessor}. La logique
 * ESCROW/claim/refund est testée dans {@code RefundProcessorTest}.
 */
@ExtendWith(MockitoExtension.class)
class SenderNoShowConfirmedListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundProcessor refundProcessor;

    private SenderNoShowConfirmedListener listener;

    @BeforeEach
    void setUp() {
        listener = new SenderNoShowConfirmedListener(paymentRepository, refundProcessor);
    }

    @Test
    void sender_no_show_with_payment_delegates_refund() {
        UUID bidId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = spy(new PaymentEntity());
        p.setBidId(bidId);
        when(p.getId()).thenReturn(paymentId);
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.of(p));

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(bidId, UUID.randomUUID(), CancellationReason.SENDER_NO_SHOW));

        verify(refundProcessor).processRefund(eq(paymentId),
                eq("PAYMENT_REFUNDED_SENDER_NO_SHOW"), eq(bidId), any(Map.class));
    }

    @Test
    void other_reason_does_not_delegate() {
        UUID bidId = UUID.randomUUID();

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(bidId, UUID.randomUUID(), CancellationReason.TRIP_CANCELLED));

        verifyNoInteractions(refundProcessor, paymentRepository);
    }

    @Test
    void no_payment_for_bid_does_not_delegate() {
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.empty());

        listener.onCancellationConfirmed(
                new CancellationConfirmedEvent(bidId, UUID.randomUUID(), CancellationReason.SENDER_NO_SHOW));

        verifyNoInteractions(refundProcessor);
    }
}
