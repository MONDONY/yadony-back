package com.yadony.api.payments;

import com.yadony.api.cancellation.CancellationReason;
import com.yadony.api.cancellation.events.CancellationConfirmedEvent;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidExpiredOnDepartureEvent;
import com.yadony.api.matching.events.BidRejectedEvent;
import com.yadony.api.matching.events.ParcelRefusedEvent;
import com.yadony.api.matching.events.VoyageurNoShowEvent;
import com.yadony.api.cancellation.events.TripCancelledEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCancelParams;
import jakarta.persistence.EntityManager;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reproduction (base H2 réelle) : un bid matérialisé depuis un fil de négociation porte son
 * paiement sur le FIL ({@code payments.negotiation_thread_id}, {@code bid_id} NULL). Chaque
 * listener de remboursement doit retrouver ce paiement — sinon l'expéditeur n'est jamais
 * remboursé (autorisation carte qui expire, dépôt mobile money gardé).
 *
 * <p>Les listeners sont instanciés à la main avec les VRAIS repositories : appeler le bean
 * Spring passerait par {@code @Async} sur un autre thread, qui ne verrait pas les lignes de la
 * transaction de test.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NegotiatedBidRefundResolutionIT {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private BidRepository bidRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @MockitoBean private RefundProcessor refundProcessor;

    private UUID threadId;
    private BidEntity negotiatedBid;
    private PaymentEntity threadPayment;

    @BeforeEach
    void setUp() {
        threadId = UUID.randomUUID();
        negotiatedBid = saveBid(threadId);
        threadPayment = savePayment(null, threadId, PaymentRail.STRIPE);
    }

    private BidEntity saveBid(UUID linkedThreadId) {
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(UUID.randomUUID());
        bid.setSenderId(UUID.randomUUID());
        bid.setStatus(BidStatus.ACCEPTED);
        bid.setLinkedNegotiationThreadId(linkedThreadId);
        return bidRepository.saveAndFlush(bid);
    }

    private PaymentEntity savePayment(UUID bidId, UUID negotiationThreadId, PaymentRail rail) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setNegotiationThreadId(negotiationThreadId);
        p.setRail(rail);
        p.setStripePaymentIntentId(rail == PaymentRail.STRIPE ? "pi_" + UUID.randomUUID() : null);
        p.setAmount(new BigDecimal("50.00"));
        p.setCommissionAmount(new BigDecimal("6.00"));
        p.setCurrency("EUR");
        p.setStatus(PaymentStatus.ESCROW);
        return paymentRepository.saveAndFlush(p);
    }

    @Test
    void negotiated_bid_cancelled_by_sender_refunds_thread_payment() {
        new BidRejectedEventListener(paymentRepository, refundProcessor).handleBidRejected(
                new BidRejectedEvent(negotiatedBid.getId(), negotiatedBid.getSenderId(), "CANCELLED_BY_SENDER"));

        verify(refundProcessor, times(1)).processRefund(eq(threadPayment.getId()),
                eq("PAYMENT_REFUNDED_BID_REJECTED"), eq(negotiatedBid.getId()), anyMap());
    }

    @Test
    void negotiated_bid_traveler_no_show_refunds_thread_payment() {
        UUID travelerId = UUID.randomUUID();
        new NoShowEventListener(paymentRepository, refundProcessor).onVoyageurNoShow(
                new VoyageurNoShowEvent(negotiatedBid.getId(), travelerId, negotiatedBid.getSenderId(), 1));

        verify(refundProcessor, times(1)).processRefund(eq(threadPayment.getId()),
                eq("PAYMENT_REFUNDED_NO_SHOW"), eq(travelerId), anyMap());
    }

    @Test
    void negotiated_bid_expired_on_departure_refunds_thread_payment() {
        new BidExpiredOnDepartureEventListener(paymentRepository, refundProcessor).handleBidExpired(
                new BidExpiredOnDepartureEvent(negotiatedBid.getId(), negotiatedBid.getSenderId(),
                        UUID.randomUUID(), UUID.randomUUID()));

        verify(refundProcessor, times(1)).processRefund(eq(threadPayment.getId()),
                eq("PAYMENT_REFUNDED_BID_EXPIRED"), eq(negotiatedBid.getId()), anyMap());
    }

    @Test
    void negotiated_parcel_refused_refunds_thread_payment() {
        new ParcelRefusedEventListener(paymentRepository, refundProcessor).onParcelRefused(
                new ParcelRefusedEvent(negotiatedBid.getId(), UUID.randomUUID(),
                        negotiatedBid.getSenderId(), "contenu interdit"));

        verify(refundProcessor, times(1)).processRefund(eq(threadPayment.getId()),
                eq("PAYMENT_REFUNDED_PARCEL_REFUSED"), any(), anyMap());
    }

    @Test
    void negotiated_sender_no_show_confirmed_refunds_thread_payment() {
        new SenderNoShowConfirmedListener(paymentRepository, refundProcessor).onCancellationConfirmed(
                new CancellationConfirmedEvent(negotiatedBid.getId(), UUID.randomUUID(),
                        CancellationReason.SENDER_NO_SHOW));

        verify(refundProcessor, times(1)).processRefund(eq(threadPayment.getId()),
                eq("PAYMENT_REFUNDED_SENDER_NO_SHOW"), eq(negotiatedBid.getId()), anyMap());
    }

    @Test
    void negotiated_mobile_money_deposit_is_resolved_too() {
        UUID mmThread = UUID.randomUUID();
        BidEntity mmBid = saveBid(mmThread);
        PaymentEntity mmPayment = savePayment(null, mmThread, PaymentRail.PAWAPAY);

        new BidRejectedEventListener(paymentRepository, refundProcessor).handleBidRejected(
                new BidRejectedEvent(mmBid.getId(), mmBid.getSenderId(), "CANCELLED_BY_SENDER"));

        verify(refundProcessor, times(1)).processRefund(eq(mmPayment.getId()),
                eq("PAYMENT_REFUNDED_BID_REJECTED"), eq(mmBid.getId()), anyMap());
    }

    @Test
    void classic_bid_keeps_its_own_payment_resolution() {
        BidEntity classic = saveBid(null);
        PaymentEntity classicPayment = savePayment(classic.getId(), null, PaymentRail.STRIPE);

        assertThat(paymentRepository.findForBid(classic.getId())).get()
                .extracting(PaymentEntity::getId).isEqualTo(classicPayment.getId());

        new BidRejectedEventListener(paymentRepository, refundProcessor).handleBidRejected(
                new BidRejectedEvent(classic.getId(), classic.getSenderId(), "CANCELLED_BY_SENDER"));

        verify(refundProcessor, times(1)).processRefund(eq(classicPayment.getId()),
                eq("PAYMENT_REFUNDED_BID_REJECTED"), eq(classic.getId()), anyMap());
        verify(refundProcessor, never()).processRefund(eq(threadPayment.getId()), any(), any(), anyMap());
    }

    @Test
    void classic_bid_without_payment_resolves_nothing() {
        BidEntity classic = saveBid(null);

        assertThat(paymentRepository.findForBid(classic.getId())).isEmpty();
        assertThat(paymentRepository.findForBid(UUID.randomUUID())).isEmpty();

        new NoShowEventListener(paymentRepository, refundProcessor).onVoyageurNoShow(
                new VoyageurNoShowEvent(classic.getId(), UUID.randomUUID(), classic.getSenderId(), 1));
        verifyNoInteractions(refundProcessor);
    }

    @Test
    void thread_payment_already_carrying_a_bid_id_is_not_picked_by_the_fallback() {
        // Un paiement de fil déjà rattaché à un AUTRE bid n'est jamais confondu.
        UUID sharedThread = UUID.randomUUID();
        BidEntity other = saveBid(null);
        savePayment(other.getId(), sharedThread, PaymentRail.STRIPE);
        BidEntity linked = saveBid(sharedThread);

        assertThat(paymentRepository.findForBid(linked.getId())).isEmpty();
    }

    /**
     * Bout en bout avec le VRAI RefundProcessor (Stripe statique simulé) : l'autorisation du
     * paiement de fil est annulée UNE fois, même si deux événements de remboursement arrivent
     * (refus puis annulation de trajet) ; un paiement déjà REFUNDED ne touche jamais Stripe.
     */
    @Test
    void negotiated_escrow_is_released_exactly_once_across_two_refund_events() {
        RefundProcessor realProcessor = realRefundProcessor();
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            piStatic.when(() -> PaymentIntent.retrieve(threadPayment.getStripePaymentIntentId())).thenReturn(pi);

            new BidRejectedEventListener(paymentRepository, realProcessor).handleBidRejected(
                    new BidRejectedEvent(negotiatedBid.getId(), negotiatedBid.getSenderId(), "CANCELLED_BY_SENDER"));
            entityManager.clear();
            new TripCancelledEventListener(paymentRepository, realProcessor).handleTripCancelled(
                    new TripCancelledEvent(negotiatedBid.getAnnouncementId(), UUID.randomUUID(),
                            List.of(negotiatedBid.getSenderId()), "vol annulé", List.of(negotiatedBid.getId())));

            verify(pi, times(1)).cancel(any(PaymentIntentCancelParams.class));
        } catch (com.stripe.exception.StripeException e) {
            throw new AssertionError(e);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM payments WHERE id = ?", String.class,
                threadPayment.getId())).isEqualTo("REFUNDED");
    }

    @Test
    void already_refunded_thread_payment_never_reaches_stripe() {
        jdbc.update("UPDATE payments SET status = 'REFUNDED' WHERE id = ?", threadPayment.getId());
        entityManager.clear();
        RefundProcessor realProcessor = realRefundProcessor();
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class)) {
            new NoShowEventListener(paymentRepository, realProcessor).onVoyageurNoShow(
                    new VoyageurNoShowEvent(negotiatedBid.getId(), UUID.randomUUID(), negotiatedBid.getSenderId(), 1));

            piStatic.verifyNoInteractions();
        }
    }

    private RefundProcessor realRefundProcessor() {
        return new RefundProcessor(paymentRepository, mock(AuditService.class), mock(AdminAlertService.class),
                mock(PawapayOperationService.class), mock(PawapaySubmissionService.class),
                mock(AdminAlertEscalator.class), mock(PlatformTransactionManager.class));
    }
}
