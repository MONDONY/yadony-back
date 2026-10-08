package com.yadony.api.payments;

import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
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
 * La logique PENDING/ESCROW/claim/refund est testée dans {@code RefundProcessorTest}.
 */
@ExtendWith(MockitoExtension.class)
class BidExpiredOnDepartureEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private RefundProcessor refundProcessor;

    private BidExpiredOnDepartureEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new BidExpiredOnDepartureEventListener(paymentRepository, refundProcessor);
    }

    @Test
    void delegates_refund_to_processor() {
        UUID bidId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = spy(new PaymentEntity());
        p.setBidId(bidId);
        when(p.getId()).thenReturn(paymentId);
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.of(p));

        listener.handleBidExpired(new BidExpiredOnDepartureEvent(
                bidId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        // L'acteur d'audit est le bid de l'événement (jamais payment.getBidId(), NULL pour un fil).
        verify(refundProcessor).processRefund(eq(paymentId),
                eq("PAYMENT_REFUNDED_BID_EXPIRED"), eq(bidId), any(Map.class));
    }

    @Test
    void handover_deadline_expiry_refunds_in_full_with_its_own_audit_reason() {
        // FLUTTER-GA : même remboursement intégral qu'au départ, motif d'audit distinct.
        UUID bidId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = spy(new PaymentEntity());
        when(p.getId()).thenReturn(paymentId);
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.of(p));

        listener.handleBidExpired(new BidExpiredOnDepartureEvent(
                bidId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "HANDOVER_DEADLINE_PASSED"));

        verify(refundProcessor).processRefund(eq(paymentId), eq("PAYMENT_REFUNDED_BID_EXPIRED"), eq(bidId),
                eq(Map.of("reason", "bid_expired_handover_deadline")));
    }

    @Test
    void no_payment_no_processor_call() {
        UUID bidId = UUID.randomUUID();
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.empty());

        listener.handleBidExpired(new BidExpiredOnDepartureEvent(
                bidId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        verifyNoInteractions(refundProcessor);
    }

    @Test
    void negotiated_thread_payment_is_refunded_with_bid_as_actor() {
        // Paiement de fil : bid_id NULL. L'acteur d'audit reste le bid de l'événement.
        UUID bidId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentEntity p = spy(new PaymentEntity());
        p.setNegotiationThreadId(UUID.randomUUID());
        when(p.getId()).thenReturn(paymentId);
        when(paymentRepository.findForBid(bidId)).thenReturn(Optional.of(p));

        listener.handleBidExpired(new BidExpiredOnDepartureEvent(
                bidId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        verify(refundProcessor).processRefund(eq(paymentId),
                eq("PAYMENT_REFUNDED_BID_EXPIRED"), eq(bidId), any(Map.class));
    }
}
