package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.admin.dto.AdminPaymentDetailResponse;
import com.yadony.api.payments.events.PaymentReleasedEvent;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.Transfer;
import com.stripe.param.RefundCreateParams;
import com.yadony.api.payments.chargeback.ChargebackRepository;
import com.stripe.param.TransferCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminPaymentControllerTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AdminAlertRepository adminAlertRepository;
    @Mock private AuditService auditService;
    @Mock private BidRepository bidRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private UserRepository userRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ChargebackRepository chargebackRepository;
    // Tâche 18, Ronde 1, point 5 : dépendances mobile money du contrôleur, injectées par
    // constructeur (plus par champ) — jamais exercées par les tests de cette classe (rail
    // STRIPE uniquement), comme les 4 mocks supplémentaires déjà passés en revue pour
    // RefundProcessorTest à la tâche 17.
    @Mock private com.yadony.api.payments.mobilemoney.MobileMoneyPayoutInitiator payoutInitiator;
    @Mock private com.yadony.api.payments.pawapay.PawapayOperationService pawapayOperations;
    @Mock private com.yadony.api.payments.pawapay.PawapaySubmissionService pawapaySubmission;
    @Mock private com.yadony.api.payments.RefundProcessor refundProcessor;
    @Mock private jakarta.persistence.EntityManager entityManager;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private AdminPaymentController controller;

    private final UUID paymentId = UUID.randomUUID();
    private final UUID threadId = UUID.randomUUID();
    private final UUID bidId = UUID.randomUUID();
    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new AdminPaymentController(paymentRepository, adminAlertRepository, auditService,
                bidRepository, announcementRepository, userRepository, eventPublisher, chargebackRepository,
                payoutInitiator, pawapayOperations, pawapaySubmission, refundProcessor, entityManager,
                transactionManager);
    }

    private PaymentEntity threadPayment(PaymentStatus status, boolean legacy, String chargeId) {
        PaymentEntity p = new PaymentEntity();
        p.setNegotiationThreadId(threadId);     // bidId left null → negotiation payment
        p.setStripePaymentIntentId("pi_xxx");
        p.setStripeChargeId(chargeId);
        p.setStatus(status);
        p.setLegacyDestinationCharge(legacy);
        p.setAmount(new BigDecimal("100.00"));
        p.setCommissionAmount(new BigDecimal("10.71"));
        return p;
    }

    /** Wire the thread → bid → announcement → traveler resolution chain. */
    private void stubResolutionChain(String travelerAccountId) {
        BidEntity bid = mock(BidEntity.class);
        when(bid.getId()).thenReturn(bidId);
        when(bid.getAnnouncementId()).thenReturn(announcementId);
        lenient().when(bid.getSenderId()).thenReturn(senderId);
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));

        AnnouncementEntity ann = mock(AnnouncementEntity.class);
        when(ann.getTravelerId()).thenReturn(travelerId);
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(ann));

        UserEntity traveler = new UserEntity();
        traveler.setStripeAccountId(travelerAccountId);
        when(userRepository.findById(travelerId)).thenReturn(Optional.of(traveler));
    }

    @Test
    void thread_payment_already_captured_transfers_net_to_traveler() throws StripeException {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_old");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        stubResolutionChain("acct_traveler");
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("succeeded"); // already captured (e.g. dashboard)
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);
            ArgumentCaptor<TransferCreateParams> captor = ArgumentCaptor.forClass(TransferCreateParams.class);
            trStatic.when(() -> Transfer.create(captor.capture())).thenReturn(mock(Transfer.class));

            ResponseEntity<AdminPaymentDetailResponse> resp = controller.forceRelease(paymentId);

            verify(pi, never()).capture();
            TransferCreateParams params = captor.getValue();
            assertThat(params.getAmount()).isEqualTo(8929L); // (100.00 - 10.71) * 100
            assertThat(params.getDestination()).isEqualTo("acct_traveler");
            assertThat(params.getSourceTransaction()).isEqualTo("ch_old");
            assertThat(params.getMetadata().get("bid_id")).isEqualTo(bidId.toString());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().status()).isEqualTo("RELEASED");
        }

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        verify(auditService).log(eq("PAYMENT"), any(), eq("ESCROW_FORCE_RELEASED"), eq(bidId), any());
        verify(eventPublisher).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void thread_payment_requires_capture_is_captured_then_transferred() throws StripeException {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_held");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        stubResolutionChain("acct_traveler");
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            when(pi.capture()).thenReturn(pi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);
            trStatic.when(() -> Transfer.create(any(TransferCreateParams.class))).thenReturn(mock(Transfer.class));

            controller.forceRelease(paymentId);

            verify(pi).capture();
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RELEASED);
        verify(eventPublisher).publishEvent(any(PaymentReleasedEvent.class));
    }

    @Test
    void legacy_payment_only_captures_no_transfer() throws StripeException {
        // Legacy destination charge: capturing routes funds via transfer_data — no explicit Transfer.
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, true, "ch_legacy");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        stubResolutionChain("acct_traveler");
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture");
            when(pi.capture()).thenReturn(pi);
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            controller.forceRelease(paymentId);

            verify(pi).capture();
            trStatic.verifyNoInteractions();
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.RELEASED);
    }

    @Test
    void payment_not_found_throws_404() {
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.forceRelease(paymentId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void payment_not_in_escrow_throws_422() {
        PaymentEntity p = threadPayment(PaymentStatus.RELEASED, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(0); // already released

        assertThatThrownBy(() -> controller.forceRelease(paymentId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("payment-not-in-escrow");
    }

    @Test
    void traveler_without_connect_account_throws_422_and_no_transfer() {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        stubResolutionChain(null); // traveler has no Stripe account
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(1);

        try (MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            assertThatThrownBy(() -> controller.forceRelease(paymentId))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                    .isEqualTo("traveler-no-connect");
            trStatic.verifyNoInteractions();
        }
    }

    // ── refund (sender) ─────────────────────────────────────────────────────────

    @Test
    void refund_capturedPayment_issuesStripeRefund() {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("succeeded"); // fonds déjà capturés
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);
            ArgumentCaptor<RefundCreateParams> captor = ArgumentCaptor.forClass(RefundCreateParams.class);
            refundStatic.when(() -> Refund.create(captor.capture())).thenReturn(mock(Refund.class));

            ResponseEntity<AdminPaymentDetailResponse> resp = controller.refund(paymentId);

            assertThat(captor.getValue().getPaymentIntent()).isEqualTo("pi_xxx");
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().status()).isEqualTo("REFUNDED");
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(auditService).log(eq("PAYMENT"), any(), eq("ESCROW_FORCE_REFUNDED"), any(), any());
    }

    @Test
    void refund_uncapturedHold_cancelsPaymentIntent() throws StripeException {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("requires_capture"); // hold non capturé
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            ResponseEntity<AdminPaymentDetailResponse> resp = controller.refund(paymentId);

            verify(pi).cancel(); // annulation du hold, pas de Refund
            refundStatic.verify(() -> Refund.create(any(RefundCreateParams.class)), never());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().status()).isEqualTo("REFUNDED");
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void refund_payment_not_found_throws_404() {
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.refund(paymentId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refund_payment_not_in_escrow_throws_422() {
        PaymentEntity p = threadPayment(PaymentStatus.RELEASED, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(0);
        assertThatThrownBy(() -> controller.refund(paymentId))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("payment-not-in-escrow");
    }

    @Test
    void refund_alreadyRefundedInStripe_isIdempotentSuccess() throws StripeException {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);
            var alreadyRefunded = mock(com.stripe.exception.InvalidRequestException.class);
            when(alreadyRefunded.getCode()).thenReturn("charge_already_refunded");
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class))).thenThrow(alreadyRefunded);

            ResponseEntity<AdminPaymentDetailResponse> resp = controller.refund(paymentId);

            // L'argent est déjà revenu côté Stripe → succès, DB passée à REFUNDED.
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().status()).isEqualTo("REFUNDED");
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void refund_alreadyCanceledHold_isIdempotentSuccess() {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("canceled"); // hold déjà levé
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);

            ResponseEntity<AdminPaymentDetailResponse> resp = controller.refund(paymentId);

            refundStatic.verify(() -> Refund.create(any(RefundCreateParams.class)), never());
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().status()).isEqualTo("REFUNDED");
        }
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void refund_stripe_error_throws_500() {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(paymentId)).thenReturn(1);
        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Refund> refundStatic = mockStatic(Refund.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);
            refundStatic.when(() -> Refund.create(any(RefundCreateParams.class)))
                    .thenThrow(mock(com.stripe.exception.InvalidRequestException.class));
            assertThatThrownBy(() -> controller.refund(paymentId))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Test
    void cancelled_bid_is_rejected_without_release() {
        // A CANCELLED trip's escrow must be REFUNDED to the sender, never transferred to the
        // traveler. force-release must refuse it (422) before flipping status or transferring.
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        BidEntity bid = mock(BidEntity.class);
        when(bid.getStatus()).thenReturn(BidStatus.CANCELLED);
        when(bidRepository.findByLinkedNegotiationThreadId(threadId)).thenReturn(Optional.of(bid));

        try (MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            assertThatThrownBy(() -> controller.forceRelease(paymentId))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                    .isEqualTo("bid-cancelled");
            trStatic.verifyNoInteractions();
        }
        verify(paymentRepository, never()).markReleasedIfEscrow(any(), any());
    }

    @Test
    void stripe_transfer_error_throws_500() throws StripeException {
        PaymentEntity p = threadPayment(PaymentStatus.ESCROW, false, "ch_x");
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(p));
        stubResolutionChain("acct_traveler");
        when(paymentRepository.markReleasedIfEscrow(eq(paymentId), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> piStatic = mockStatic(PaymentIntent.class);
             MockedStatic<Transfer> trStatic = mockStatic(Transfer.class)) {
            PaymentIntent pi = mock(PaymentIntent.class);
            when(pi.getStatus()).thenReturn("succeeded");
            piStatic.when(() -> PaymentIntent.retrieve("pi_xxx")).thenReturn(pi);
            trStatic.when(() -> Transfer.create(any(TransferCreateParams.class)))
                    .thenThrow(mock(com.stripe.exception.InvalidRequestException.class));

            assertThatThrownBy(() -> controller.forceRelease(paymentId))
                    .isInstanceOf(YadonyBusinessException.class)
                    .extracting(e -> ((YadonyBusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
        verify(eventPublisher, never()).publishEvent(any());
    }
}
