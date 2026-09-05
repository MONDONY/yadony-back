package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.matching.events.BidAcceptedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidAcceptedEventListenerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;
    @Mock private UserRepository userRepository;
    @Mock private BidRepository bidRepository;
    private BidAcceptedEventListener listener;

    private final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new BidAcceptedEventListener(paymentRepository, auditService, userRepository, bidRepository);
    }

    private PaymentEntity paymentFor(UUID bidId, PaymentStatus status, boolean legacy) {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setStripePaymentIntentId("pi_xxx");
        p.setStatus(status);
        p.setLegacyDestinationCharge(legacy);
        p.setAmount(new BigDecimal("30.00"));
        p.setCommissionAmount(new BigDecimal("3.60"));
        return p;
    }

    /** Creates an event with the shared travelerId so userRepository can be stubbed centrally. */
    private BidAcceptedEvent eventFor(UUID bidId) {
        return new BidAcceptedEvent(bidId, UUID.randomUUID(), travelerId, UUID.randomUUID());
    }

    private UserEntity eligibleTraveler() {
        UserEntity t = new UserEntity();
        t.setStripeAccountId("acct_traveler");
        t.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        return t;
    }

    /** Stub bidRepository to return a STRIPE bid so the listener reaches the Stripe path. */
    private BidEntity stubStripeBid(UUID bidId) {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        return bid;
    }

    /** Stub bidRepository to return a MOBILE_MONEY bid — le listener doit s'arrêter net (Ronde 1, point 5). */
    private BidEntity stubMobileMoneyBid(UUID bidId) {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.MOBILE_MONEY);
        bid.setStatus(BidStatus.AWAITING_PAYMENT);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        return bid;
    }

    // ── Ronde 1, point 5 : rail mobile money ──────────────────────────────────

    /**
     * Preuve que le listener s'arrête AVANT tout accès à paymentRepository/auditService —
     * pas seulement qu'il ne capture rien. Sans cette garde explicite, ce même test aurait pu
     * passer « par coïncidence » (payment absent, ou statut != ESCROW) sans jamais prouver
     * que le rail mobile money est structurellement exclu.
     */
    @Test
    void skips_mobileMoney_bid_entirely() {
        UUID bidId = UUID.randomUUID();
        stubMobileMoneyBid(bidId);

        listener.onBidAccepted(eventFor(bidId));

        verifyNoInteractions(paymentRepository, auditService);
    }

    // ── Existing behaviour ────────────────────────────────────────────────────

    @Test
    void captures_PI_for_non_legacy_payment_in_escrow() throws StripeException {
        UUID bidId = UUID.randomUUID();
        stubStripeBid(bidId);
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.ESCROW, false);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        when(paymentRepository.markCapturedIfEscrow(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(eligibleTraveler()));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.capture()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.onBidAccepted(eventFor(bidId));

            verify(pi).capture();
            verify(auditService).log(eq("PAYMENT"), any(), eq("PAYMENT_CAPTURED_ON_PLATFORM"), any(), any());
        }
    }

    @Test
    void skips_capture_for_legacy_payment() {
        UUID bidId = UUID.randomUUID();
        stubStripeBid(bidId);
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.ESCROW, true);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            listener.onBidAccepted(eventFor(bidId));
            mocked.verifyNoInteractions();
        }
        verify(auditService, never()).log(eq("PAYMENT"), any(), eq("PAYMENT_CAPTURED_ON_PLATFORM"), any(), any());
    }

    @Test
    void no_op_when_payment_not_found() {
        UUID bidId = UUID.randomUUID();
        stubStripeBid(bidId);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.empty());

        assertThatNoException().isThrownBy(() -> listener.onBidAccepted(eventFor(bidId)));
        verifyNoInteractions(auditService);
    }

    @Test
    void no_op_when_status_not_escrow() {
        UUID bidId = UUID.randomUUID();
        stubStripeBid(bidId);
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.RELEASED, false);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            listener.onBidAccepted(eventFor(bidId));
            mocked.verifyNoInteractions();
        }
    }

    @Test
    void capture_failure_is_logged_but_not_thrown() throws StripeException {
        UUID bidId = UUID.randomUUID();
        stubStripeBid(bidId);
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.ESCROW, false);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        when(paymentRepository.markCapturedIfEscrow(any(), any())).thenReturn(1);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(eligibleTraveler()));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.capture()).thenThrow(mock(com.stripe.exception.InvalidRequestException.class));
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            assertThatNoException().isThrownBy(() -> listener.onBidAccepted(eventFor(bidId)));
        }
    }

    // ── New: pre-capture re-verification (PR-3) ───────────────────────────────

    @Test
    void cancels_PI_and_bid_when_traveler_reverted_to_pending_before_capture() throws StripeException {
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.ESCROW, false);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        UserEntity ineligibleTraveler = new UserEntity();
        ineligibleTraveler.setStripeAccountId("acct_traveler");
        ineligibleTraveler.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(ineligibleTraveler));

        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(bidRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.onBidAccepted(eventFor(bidId));

            verify(pi).cancel();
            verify(pi, never()).capture();
            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            verify(auditService).log(eq("BID"), any(), eq("BID_CANCELLED_TRAVELER_INELIGIBLE"), any(), any());
        }
    }

    @Test
    void cancels_PI_and_bid_when_traveler_not_found_before_capture() throws StripeException {
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.ESCROW, false);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));
        when(userRepository.findById(travelerId)).thenReturn(Optional.empty());

        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(bidRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            listener.onBidAccepted(eventFor(bidId));

            verify(pi).cancel();
            verify(pi, never()).capture();
            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        }
    }

    @Test
    void pi_cancel_stripe_error_does_not_throw_when_traveler_ineligible() throws StripeException {
        UUID bidId = UUID.randomUUID();
        PaymentEntity payment = paymentFor(bidId, PaymentStatus.ESCROW, false);
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        UserEntity ineligibleTraveler = new UserEntity();
        ineligibleTraveler.setStripeAccountStatus(StripeAccountStatus.DISABLED);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(ineligibleTraveler));

        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenThrow(mock(com.stripe.exception.InvalidRequestException.class));
            mocked.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            // Must not throw — error is caught + logged
            assertThatNoException().isThrownBy(() -> listener.onBidAccepted(eventFor(bidId)));
            // Bid must NOT be cancelled — PI is still live; ops must reconcile via audit log
            assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
            // Audit log must be written so ops can see the irreconcilable PI
            verify(auditService).log(eq("BID"), any(), eq("BID_CANCEL_PI_FAILED"), any(), any());
        }
    }
}
