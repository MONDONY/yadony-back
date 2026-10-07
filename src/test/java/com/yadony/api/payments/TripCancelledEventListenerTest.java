package com.yadony.api.payments;

import com.yadony.api.cancellation.events.TripCancelledEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TripCancelledEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundProcessor refundProcessor;

    private TripCancelledEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new TripCancelledEventListener(paymentRepository, refundProcessor);
    }

    private PaymentEntity payment(UUID id) {
        PaymentEntity p = spy(new PaymentEntity());
        doReturn(id).when(p).getId();
        return p;
    }

    private TripCancelledEvent event(UUID... bidIds) {
        return new TripCancelledEvent(UUID.randomUUID(), UUID.randomUUID(),
                List.of(UUID.randomUUID()), "vol annulé", List.of(bidIds));
    }

    @Test
    void classic_bid_payment_is_delegated_to_refund_processor() {
        UUID bidId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = payment(paymentId);
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.of(p));

        listener.handleTripCancelled(event(bidId));

        ArgumentCaptor<Map<String, String>> payload = ArgumentCaptor.forClass(Map.class);
        verify(refundProcessor).processRefund(eq(paymentId), eq("PAYMENT_REFUNDED"),
                eq(bidId), payload.capture());
        assertThat(payload.getValue())
                .containsEntry("bidId", bidId.toString())
                .containsEntry("reason", "trip_cancelled");
    }

    @Test
    void negotiation_thread_payment_refunded_via_thread_fallback() {
        // Regression: a cancelled negotiation/dedicated trip keys its escrow on the thread
        // (bid_id NULL). findForBid resolves the thread payment (requête testée sur H2 dans
        // NegotiatedBidRefundResolutionIT).
        UUID bidId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        PaymentEntity p = payment(paymentId);
        p.setNegotiationThreadId(UUID.randomUUID());
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.of(p));

        listener.handleTripCancelled(event(bidId));

        verify(refundProcessor).processRefund(eq(paymentId), eq("PAYMENT_REFUNDED"), eq(bidId), any());
    }

    @Test
    void no_payment_anywhere_is_a_no_op() {
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> listener.handleTripCancelled(event(bidId)));
        verifyNoInteractions(refundProcessor);
    }

    @Test
    void empty_bid_list_is_a_no_op() {
        TripCancelledEvent emptyEvent = new TripCancelledEvent(UUID.randomUUID(), UUID.randomUUID(),
                List.of(), "vol annulé", List.of());

        listener.handleTripCancelled(emptyEvent);

        verifyNoInteractions(paymentRepository, refundProcessor);
    }

    @Test
    void one_failure_does_not_abort_remaining_refunds() {
        UUID bid1 = UUID.randomUUID(), bid2 = UUID.randomUUID();
        UUID pid1 = UUID.randomUUID(), pid2 = UUID.randomUUID();
        PaymentEntity p1 = payment(pid1);
        PaymentEntity p2 = payment(pid2);
        when(paymentRepository.findForBid(bid1)).thenReturn(Optional.of(p1));
        when(paymentRepository.findForBid(bid2)).thenReturn(Optional.of(p2));
        when(refundProcessor.processRefund(eq(pid1), any(), any(), any()))
                .thenThrow(new IllegalStateException("stripe down"));

        listener.handleTripCancelled(event(bid1, bid2));

        verify(refundProcessor).processRefund(eq(pid2), any(), any(), any());
    }
}
