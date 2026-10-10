package com.yadony.api.payments;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.matching.BidStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentStripeResyncServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentService paymentService;
    @Mock private EscrowCaptureService escrowCapture;
    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private PaymentStripeResyncService service;
    private final UUID paymentId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        service = new PaymentStripeResyncService(paymentRepository, paymentService, escrowCapture, bidRepository,
                auditService, transactionManager);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setNegotiationThreadId(UUID.randomUUID());
        payment.setStripePaymentIntentId("pi_r");
        payment.setAmount(new BigDecimal("64.50"));
        payment.setCommissionAmount(new BigDecimal("6.91"));
        payment.setCurrency("EUR");
        lenient().when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    }

    private static PaymentIntent pi(String status, long amount) {
        PaymentIntent pi = mock(PaymentIntent.class);
        lenient().when(pi.getStatus()).thenReturn(status);
        lenient().when(pi.getAmount()).thenReturn(amount);
        lenient().when(pi.getCurrency()).thenReturn("eur");
        lenient().when(pi.getAmountCapturable()).thenReturn("requires_capture".equals(status) ? amount : 0L);
        return pi;
    }

    private PaymentStripeResyncService.Result run(PaymentIntent pi) {
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.retrieve(eq("pi_r"), any(PaymentIntentRetrieveParams.class), isNull()))
                    .thenReturn(pi);
            return service.resync(paymentId, adminId);
        }
    }

    private YadonyBusinessException runExpectingError(PaymentIntent pi) {
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.retrieve(eq("pi_r"), any(PaymentIntentRetrieveParams.class), isNull()))
                    .thenReturn(pi);
            return catchBusiness(() -> service.resync(paymentId, adminId));
        }
    }

    private static YadonyBusinessException catchBusiness(Runnable call) {
        try {
            call.run();
        } catch (YadonyBusinessException e) {
            return e;
        }
        throw new AssertionError("YadonyBusinessException attendue");
    }

    // ── PENDING ──────────────────────────────────────────────────────────────

    @Test
    void pendingAuthorized_appliesTheAmountCapturableWebhookTreatment() {
        payment.setStatus(PaymentStatus.PENDING);
        PaymentIntent pi = pi("requires_capture", 6450);
        doAnswer(inv -> { payment.setStatus(PaymentStatus.ESCROW); return null; })
                .when(paymentService).applyPaymentEscrowActive(pi);

        PaymentStripeResyncService.Result r = run(pi);

        verify(paymentService).applyPaymentEscrowActive(pi);
        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.ESCROW_ACTIVATED);
        assertThat(r.before().status()).isEqualTo("PENDING");
        assertThat(r.after().status()).isEqualTo("ESCROW");
        assertThat(r.message()).contains("capture de la négociation");
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("ADMIN_PAYMENT_RESYNC_STRIPE"), eq(adminId),
                argThat(m -> "ESCROW_ACTIVATED".equals(m.get("action")) && "pi_r".equals(m.get("piId"))));
        verifyNoInteractions(escrowCapture);
    }

    @Test
    void pendingSucceeded_activatesEscrowAndRecordsCapture() {
        payment.setStatus(PaymentStatus.PENDING);
        PaymentIntent pi = pi("succeeded", 6450);
        doAnswer(inv -> { payment.setStatus(PaymentStatus.ESCROW); return null; })
                .when(paymentService).applyPaymentEscrowActive(pi);

        PaymentStripeResyncService.Result r = run(pi);

        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.ESCROW_ACTIVATED);
        // Écriture ciblée, jamais de save de l'entité.
        verify(paymentRepository).markCapturedIfEscrow(eq(paymentId), any());
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(escrowCapture);
    }

    @Test
    void pendingClassicBidAlreadyAccepted_activatesThenCaptures() {
        // Revue #487, point 4 : BidAcceptedEvent est passé pendant que le paiement était PENDING,
        // aucune capture n'a eu lieu ; le modèle actuel capture à l'acceptation : on la lance.
        payment.setStatus(PaymentStatus.PENDING);
        payment.setNegotiationThreadId(null);
        UUID bidId = UUID.randomUUID();
        payment.setBidId(bidId);
        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.ACCEPTED);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        PaymentIntent pi = pi("requires_capture", 6450);
        doAnswer(inv -> { payment.setStatus(PaymentStatus.ESCROW); return null; })
                .when(paymentService).applyPaymentEscrowActive(pi);
        when(escrowCapture.ensureCaptured(paymentId, "admin-resync-stripe"))
                .thenReturn(new EscrowCaptureService.Outcome("ch_1", true));

        PaymentStripeResyncService.Result r = run(pi);

        org.mockito.InOrder order = inOrder(paymentService, escrowCapture);
        order.verify(paymentService).applyPaymentEscrowActive(pi);
        order.verify(escrowCapture).ensureCaptured(paymentId, "admin-resync-stripe");
        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.ESCROW_ACTIVATED);
        assertThat(r.after().stripeStatus()).isEqualTo("succeeded");
        assertThat(r.message()).contains("séquestre capturé");
    }

    @Test
    void pendingClassicBidAccepted_captureFails_escrowStaysActivated() {
        payment.setStatus(PaymentStatus.PENDING);
        payment.setNegotiationThreadId(null);
        UUID bidId = UUID.randomUUID();
        payment.setBidId(bidId);
        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.IN_TRANSIT);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        PaymentIntent pi = pi("requires_capture", 6450);
        doAnswer(inv -> { payment.setStatus(PaymentStatus.ESCROW); return null; })
                .when(paymentService).applyPaymentEscrowActive(pi);
        when(escrowCapture.ensureCaptured(any(), any()))
                .thenThrow(new EscrowCaptureService.EscrowCaptureException("refus", "requires_capture", null));

        PaymentStripeResyncService.Result r = run(pi);

        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.ESCROW_ACTIVATED);
        assertThat(r.after().status()).isEqualTo("ESCROW");
        assertThat(r.after().stripeStatus()).isEqualTo("requires_capture");
        assertThat(r.message()).contains("capture impossible pour l'instant").contains("à la livraison");
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("ADMIN_PAYMENT_RESYNC_STRIPE"), eq(adminId), anyMap());
    }

    @Test
    void pendingClassicBidNotYetAccepted_saysWhenItWillBeCharged() {
        payment.setStatus(PaymentStatus.PENDING);
        payment.setNegotiationThreadId(null);
        UUID bidId = UUID.randomUUID();
        payment.setBidId(bidId);
        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.PENDING);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        PaymentStripeResyncService.Result r = run(pi("requires_capture", 6450));

        assertThat(r.message()).contains("l'encaissement aura lieu à l'acceptation du colis");
        verifyNoInteractions(escrowCapture);
    }

    @Test
    void pendingCanceled_appliesCanceledWebhookTreatment() {
        payment.setStatus(PaymentStatus.PENDING);
        PaymentIntent pi = pi("canceled", 6450);
        PaymentStripeResyncService.Result r = run(pi);
        verify(paymentService).applyPaymentIntentCanceled(pi);
        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.MARKED_CANCELLED);
    }

    @Test
    void pendingPaymentFailed_appliesFailedWebhookTreatment() {
        payment.setStatus(PaymentStatus.PENDING);
        PaymentIntent pi = pi("requires_payment_method", 6450);
        when(pi.getLastPaymentError()).thenReturn(new StripeError());
        PaymentStripeResyncService.Result r = run(pi);
        verify(paymentService).applyPaymentFailed(pi);
        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.MARKED_FAILED);
    }

    @Test
    void pendingNotYetAttempted_isAlreadyInSync_withoutAudit() {
        payment.setStatus(PaymentStatus.PENDING);
        PaymentIntent pi = pi("requires_payment_method", 6450);
        PaymentStripeResyncService.Result r = run(pi);
        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.ALREADY_IN_SYNC);
        assertThat(r.changed()).isFalse();
        verifyNoInteractions(paymentService, auditService);
    }

    @Test
    void pendingInProgress_isAlreadyInSync() {
        payment.setStatus(PaymentStatus.PENDING);
        assertThat(run(pi("requires_action", 6450)).action())
                .isEqualTo(PaymentStripeResyncService.Action.ALREADY_IN_SYNC);
        verifyNoInteractions(paymentService);
    }

    // ── ESCROW ───────────────────────────────────────────────────────────────

    @Test
    void escrowNegotiationAuthorized_isCapturedThroughTheSharedPath() {
        payment.setStatus(PaymentStatus.ESCROW);
        PaymentIntent pi = pi("requires_capture", 6450);
        doAnswer(inv -> { payment.setCapturedAt(Instant.now()); return new EscrowCaptureService.Outcome("ch_1", true); })
                .when(escrowCapture).ensureCaptured(paymentId, "admin-resync-stripe");

        PaymentStripeResyncService.Result r = run(pi);

        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.ESCROW_CAPTURED);
        assertThat(r.before().capturedAt()).isNull();
        assertThat(r.after().capturedAt()).isNotNull();
        assertThat(r.after().stripeStatus()).isEqualTo("succeeded");
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("ADMIN_PAYMENT_RESYNC_STRIPE"), eq(adminId), anyMap());
    }

    @Test
    void escrowCaptureFailure_is409WithoutAudit() {
        payment.setStatus(PaymentStatus.ESCROW);
        when(escrowCapture.ensureCaptured(any(), any()))
                .thenThrow(new EscrowCaptureService.EscrowCaptureException("refus", "requires_capture", null));

        YadonyBusinessException e = runExpectingError(pi("requires_capture", 6450));

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo("escrow-capture-failed");
        verifyNoInteractions(auditService);
    }

    @Test
    void escrowClassicBidNotAccepted_isANormalAuthorization() {
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setNegotiationThreadId(null);
        UUID bidId = UUID.randomUUID();
        payment.setBidId(bidId);
        BidEntity bid = new BidEntity();
        bid.setStatus(BidStatus.PENDING);
        when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));

        assertThat(run(pi("requires_capture", 6450)).action())
                .isEqualTo(PaymentStripeResyncService.Action.ALREADY_IN_SYNC);
        verifyNoInteractions(escrowCapture);
    }

    @Test
    void escrowLegacyAuthorized_isInSync() {
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setLegacyDestinationCharge(true);
        assertThat(run(pi("requires_capture", 6450)).message()).contains("legacy");
        verifyNoInteractions(escrowCapture);
    }

    @Test
    void escrowSucceededWithoutCapturedAt_recordsCapture() {
        payment.setStatus(PaymentStatus.ESCROW);
        PaymentStripeResyncService.Result r = run(pi("succeeded", 6450));
        verify(paymentRepository).markCapturedIfEscrow(eq(paymentId), any());
        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.CAPTURE_RECORDED);
    }

    @Test
    void escrowCaptured_isIdempotentNoOp() {
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setCapturedAt(Instant.now());
        PaymentStripeResyncService.Result r = run(pi("succeeded", 6450));
        assertThat(r.action()).isEqualTo(PaymentStripeResyncService.Action.ALREADY_IN_SYNC);
        verify(paymentRepository, never()).markCapturedIfEscrow(any(), any());
        verifyNoInteractions(auditService, escrowCapture, paymentService);
    }

    @Test
    void escrowAuthorizationExpired_is409WithoutWrite() {
        payment.setStatus(PaymentStatus.ESCROW);
        YadonyBusinessException e = runExpectingError(pi("canceled", 6450));
        assertThat(e.getErrorCode()).isEqualTo("authorization-expired");
        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(paymentService, escrowCapture, auditService);
    }

    @Test
    void amountMismatch_is409WithoutWrite() {
        payment.setStatus(PaymentStatus.PENDING);
        YadonyBusinessException e = runExpectingError(pi("requires_capture", 9999));
        assertThat(e.getErrorCode()).isEqualTo("amount-mismatch");
        verifyNoInteractions(paymentService, auditService);
    }

    // ── Statuts clos ─────────────────────────────────────────────────────────

    @Test
    void releasedAndCaptured_isInSync() {
        payment.setStatus(PaymentStatus.RELEASED);
        assertThat(run(pi("succeeded", 6450)).action()).isEqualTo(PaymentStripeResyncService.Action.ALREADY_IN_SYNC);
    }

    @Test
    void refundedAndCanceled_isInSync() {
        payment.setStatus(PaymentStatus.REFUNDED);
        assertThat(run(pi("canceled", 6450)).action()).isEqualTo(PaymentStripeResyncService.Action.ALREADY_IN_SYNC);
    }

    @Test
    void cancelledButCapturedAtStripe_isNotSupported() {
        payment.setStatus(PaymentStatus.CANCELLED);
        assertThat(runExpectingError(pi("succeeded", 6450)).getErrorCode()).isEqualTo("resync-not-supported");
    }

    @Test
    void releasedButNotCaptured_isNotSupported() {
        payment.setStatus(PaymentStatus.RELEASED);
        assertThat(runExpectingError(pi("requires_capture", 6450)).getErrorCode()).isEqualTo("resync-not-supported");
    }

    @Test
    void failedAndCanceled_isInSync() {
        payment.setStatus(PaymentStatus.FAILED);
        assertThat(run(pi("canceled", 6450)).action()).isEqualTo(PaymentStripeResyncService.Action.ALREADY_IN_SYNC);
    }

    // ── Erreurs ──────────────────────────────────────────────────────────────

    @Test
    void unknownPayment_is404() {
        UUID other = UUID.randomUUID();
        when(paymentRepository.findById(other)).thenReturn(Optional.empty());
        YadonyBusinessException e = catchBusiness(() -> service.resync(other, adminId));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void mobileMoneyPayment_is422() {
        payment.setRail(PaymentRail.PAWAPAY);
        YadonyBusinessException e = catchBusiness(() -> service.resync(paymentId, adminId));
        assertThat(e.getErrorCode()).isEqualTo("not-a-card-payment");
    }

    @Test
    void paymentIntentMissingAtStripe_is422() {
        payment.setStatus(PaymentStatus.ESCROW);
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.retrieve(eq("pi_r"), any(PaymentIntentRetrieveParams.class), isNull()))
                    .thenThrow(new InvalidRequestException("No such payment_intent", null, "req", "resource_missing", 404, null));
            YadonyBusinessException e = catchBusiness(() -> service.resync(paymentId, adminId));
            assertThat(e.getErrorCode()).isEqualTo("payment-intent-not-found");
            assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Test
    void stripeDown_is502() {
        payment.setStatus(PaymentStatus.ESCROW);
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.retrieve(eq("pi_r"), any(PaymentIntentRetrieveParams.class), isNull()))
                    .thenThrow(new ApiConnectionException("timeout"));
            YadonyBusinessException e = catchBusiness(() -> service.resync(paymentId, adminId));
            assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }
    }

    @Test
    void otherInvalidRequest_is502() {
        payment.setStatus(PaymentStatus.ESCROW);
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.retrieve(eq("pi_r"), any(PaymentIntentRetrieveParams.class), isNull()))
                    .thenThrow(new InvalidRequestException("bad", null, "req", "parameter_invalid", 400, null));
            assertThat(catchBusiness(() -> service.resync(paymentId, adminId)).getErrorCode()).isEqualTo("stripe-unavailable");
        }
    }

    @Test
    void unknownStripeStatus_isNotSupported() {
        payment.setStatus(PaymentStatus.ESCROW);
        assertThat(runExpectingError(pi("processing", 6450)).getErrorCode()).isEqualTo("resync-not-supported");
    }
}
