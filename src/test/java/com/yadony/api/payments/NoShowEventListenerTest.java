package com.yadony.api.payments;

import com.yadony.api.matching.events.VoyageurNoShowEvent;
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
 * Contrat de délégation : le listener délègue à {@link RefundProcessor}.
 * La logique ESCROW/claim/refund est testée dans {@code RefundProcessorTest}.
 */
@ExtendWith(MockitoExtension.class)
class NoShowEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundProcessor refundProcessor;

    private NoShowEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new NoShowEventListener(paymentRepository, refundProcessor);
    }

    @Test
    void delegates_refund_to_processor() {
        UUID bidId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = spy(new PaymentEntity());
        p.setBidId(bidId);
        when(p.getId()).thenReturn(paymentId);
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.of(p));

        listener.onVoyageurNoShow(
                new VoyageurNoShowEvent(bidId, travelerId, UUID.randomUUID(), 1));

        verify(refundProcessor).processRefund(eq(paymentId),
                eq("PAYMENT_REFUNDED_NO_SHOW"), eq(travelerId), any(Map.class));
    }

    @Test
    void no_payment_no_processor_call() {
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.empty());

        listener.onVoyageurNoShow(
                new VoyageurNoShowEvent(bidId, UUID.randomUUID(), UUID.randomUUID(), 1));

        verifyNoInteractions(refundProcessor);
    }
}
