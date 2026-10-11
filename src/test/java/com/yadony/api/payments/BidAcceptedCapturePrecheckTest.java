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
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Focused tests for the PR-3 pre-capture eligibility re-check in BidAcceptedEventListener.
 * Covers the two main scenarios: traveler eligible (capture proceeds) vs traveler reverted
 * to PENDING_ONBOARDING (PI cancelled, bid set to CANCELLED).
 */
@ExtendWith(MockitoExtension.class)
class BidAcceptedCapturePrecheckTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;
    @Mock private UserRepository userRepository;
    @Mock private BidRepository bidRepository;
    @Mock private EscrowCaptureService escrowCapture;
    private BidAcceptedEventListener listener;

    private final UUID travelerId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        listener = new BidAcceptedEventListener(paymentRepository, auditService, userRepository, bidRepository,
                escrowCapture, org.springframework.transaction.support.TransactionOperations.withoutTransaction());
    }

    private PaymentEntity escrowPayment() {
        PaymentEntity p = new PaymentEntity();
        p.setBidId(bidId);
        p.setStripePaymentIntentId("pi_test");
        p.setStatus(PaymentStatus.ESCROW);
        p.setLegacyDestinationCharge(false);
        p.setAmount(new BigDecimal("50.00"));
        p.setCommissionAmount(new BigDecimal("6.00"));
        return p;
    }

    private BidAcceptedEvent event() {
        return new BidAcceptedEvent(bidId, UUID.randomUUID(), travelerId, UUID.randomUUID());
    }

    // ── Scenario 1: traveler ONBOARDING_COMPLETE → capture proceeds ───────────

    @Test
    void traveler_onboarding_complete_capture_proceeds_normally() throws StripeException {
        BidEntity bid = new BidEntity();
        bid.setPaymentMethod(PaymentMethod.STRIPE);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        PaymentEntity payment = escrowPayment();
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_ok");
        traveler.setStripeAccountStatus(StripeAccountStatus.ONBOARDING_COMPLETE);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
        when(escrowCapture.ensureCaptured(any(), eq("bid-accepted")))
                .thenReturn(new EscrowCaptureService.Outcome("ch_ok", true));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            assertThatNoException().isThrownBy(() -> listener.onBidAccepted(event()));

            // Capture déléguée au service partagé (clé capture-<id>, montant attendu, hors transaction)
            verify(escrowCapture).ensureCaptured(payment.getId(), "bid-accepted");
            mocked.verifyNoInteractions();
        }
    }

    // ── Scenario 2: traveler reverted to PENDING_ONBOARDING before capture ────

    @Test
    void traveler_reverted_to_pending_pi_cancelled_bid_cancelled() throws StripeException {
        PaymentEntity payment = escrowPayment();
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_ok");
        traveler.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        when(bidRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenReturn(pi);
            mocked.when(() -> PaymentIntent.retrieve("pi_test")).thenReturn(pi);

            listener.onBidAccepted(event());

            // PI must be cancelled
            verify(pi).cancel();
            // capture must NOT be called
            verify(pi, never()).capture();
            // bid must be set to CANCELLED (relu sous verrou, colis avant paiement)
            assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
            verify(bidRepository).lockForUpdate(bidId);
            verify(bidRepository).save(bid);
            // payment passé CANCELLED par écriture ciblée (jamais un save de l'entité lue avant l'appel Stripe)
            verify(paymentRepository).markCancelledIfEscrow(payment.getId());
            verify(paymentRepository, never()).save(any());
            verifyNoInteractions(escrowCapture);
            // audit entry for the cancellation
            verify(auditService).log(eq("BID"), any(), eq("BID_CANCELLED_TRAVELER_INELIGIBLE"), any(), any());
        }
    }

    // ── Scenario 3: pi.cancel() throws StripeException → audit log written ────

    @Test
    void traveler_ineligible_pi_cancel_throws_stripe_exception_audit_log_written() throws StripeException {
        PaymentEntity payment = escrowPayment();
        when(paymentRepository.findByBidId(bidId)).thenReturn(Optional.of(payment));

        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId("acct_ok");
        traveler.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));

        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.cancel()).thenThrow(mock(StripeException.class));
            mocked.when(() -> PaymentIntent.retrieve("pi_test")).thenReturn(pi);

            // Must not propagate the StripeException
            assertThatNoException().isThrownBy(() -> listener.onBidAccepted(event()));

            // PI cancel was attempted
            verify(pi).cancel();
            // Payment status must NOT have been changed (PI is still live)
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.ESCROW);
            verify(paymentRepository, never()).save(any());
            verify(paymentRepository, never()).markCancelledIfEscrow(any());
            // Bid must NOT have been cancelled (inconsistent state — ops must intervene)
            assertThat(bid.getStatus()).isEqualTo(BidStatus.ACCEPTED);
            // Audit log must be written with failure event so ops can reconcile (Fix 2)
            verify(auditService).log(
                    eq("BID"),
                    any(),
                    eq("BID_CANCEL_PI_FAILED"),
                    eq(travelerId),
                    argThat(map -> "traveler_not_eligible_pi_cancel_failed".equals(map.get("reason"))
                            && "pi_test".equals(map.get("pi_id")))
            );
        }
    }
}
