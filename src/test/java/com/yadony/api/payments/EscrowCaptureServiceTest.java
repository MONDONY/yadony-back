package com.yadony.api.payments;

import com.stripe.exception.InvalidRequestException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCaptureParams;
import com.stripe.param.PaymentIntentRetrieveParams;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.common.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EscrowCaptureServiceTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private AuditService auditService;
    @Mock private AdminAlertEscalator alertEscalator;

    private EscrowCaptureService service;
    private final UUID paymentId = UUID.randomUUID();
    private PaymentEntity payment;

    @BeforeEach
    void setUp() {
        service = new EscrowCaptureService(paymentRepository, auditService, alertEscalator);
        payment = new PaymentEntity();
        ReflectionTestUtils.setField(payment, "id", paymentId);
        payment.setNegotiationThreadId(UUID.randomUUID());
        payment.setStripePaymentIntentId("pi_nego");
        payment.setStatus(PaymentStatus.ESCROW);
        payment.setAmount(new BigDecimal("64.50"));
        payment.setCommissionAmount(new BigDecimal("6.91"));
        payment.setCurrency("EUR");
        lenient().when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    }

    private PaymentIntent pi(String status, long amount, long capturable) {
        PaymentIntent pi = mock(PaymentIntent.class);
        lenient().when(pi.getStatus()).thenReturn(status);
        lenient().when(pi.getAmount()).thenReturn(amount);
        lenient().when(pi.getAmountCapturable()).thenReturn(capturable);
        lenient().when(pi.getCurrency()).thenReturn("eur");
        lenient().when(pi.getLatestCharge()).thenReturn("ch_nego");
        return pi;
    }

    private static void stubRetrieve(MockedStatic<PaymentIntent> mocked, PaymentIntent pi) {
        mocked.when(() -> PaymentIntent.retrieve(eq("pi_nego"), any(PaymentIntentRetrieveParams.class), isNull()))
                .thenReturn(pi);
    }

    @Test
    void requiresCapture_marksThenCapturesExpectedAmountWithStableKey() throws Exception {
        PaymentIntent pi = pi("requires_capture", 6450L, 6450L);
        when(pi.capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class))).thenReturn(pi);
        when(paymentRepository.markCapturedIfEscrow(eq(paymentId), any())).thenReturn(1);

        EscrowCaptureService.Outcome outcome;
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            stubRetrieve(mocked, pi);
            outcome = service.ensureCaptured(paymentId, "delivery");
        }

        ArgumentCaptor<PaymentIntentCaptureParams> params = ArgumentCaptor.forClass(PaymentIntentCaptureParams.class);
        ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
        InOrder order = inOrder(paymentRepository, pi);
        order.verify(paymentRepository).markCapturedIfEscrow(eq(paymentId), any());
        order.verify(pi).capture(params.capture(), options.capture());
        assertThat(params.getValue().getAmountToCapture()).isEqualTo(6450L);
        assertThat(options.getValue().getIdempotencyKey()).isEqualTo("capture-" + paymentId);
        assertThat(outcome.capturedNow()).isTrue();
        assertThat(outcome.chargeId()).isEqualTo("ch_nego");
        // Charge id par écriture ciblée : l'entité lue avant l'appel Stripe n'est jamais enregistrée.
        verify(paymentRepository).setStripeChargeIdIfMissing(paymentId, "ch_nego");
        verify(paymentRepository, never()).save(any());
        assertThat(payment.getStripeChargeId()).isNull();
        // Capture réussie : l'alerte d'un échec précédent est close.
        verify(alertEscalator).resolveOpen("ESCROW_CAPTURE_FAILED_" + paymentId);
        verify(auditService).log(eq("PAYMENT"), eq(paymentId), eq("PAYMENT_CAPTURED_ON_PLATFORM"), isNull(),
                argThat(m -> "delivery".equals(m.get("source")) && Long.valueOf(6450L).equals(m.get("amountToCapture"))));
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    void requiresCapture_withCapturedAtAlreadySet_stillCaptures() throws Exception {
        // Ancien listener : captured_at posé alors que la capture avait échoué.
        payment.setCapturedAt(Instant.parse("2026-10-07T10:00:00Z"));
        payment.setStripeChargeId("ch_old");
        PaymentIntent pi = pi("requires_capture", 6450L, 6450L);
        when(pi.capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class))).thenReturn(pi);
        when(paymentRepository.markCapturedIfEscrow(eq(paymentId), any())).thenReturn(0);
        when(paymentRepository.lockIfEscrow(paymentId)).thenReturn(1); // toujours ESCROW, verrou pris

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            stubRetrieve(mocked, pi);
            assertThat(service.ensureCaptured(paymentId, "delivery").capturedNow()).isTrue();
        }
        verify(pi).capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class));
        assertThat(payment.getCapturedAt()).isEqualTo(Instant.parse("2026-10-07T10:00:00Z"));
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void succeeded_doesNotCaptureAndBackfillsCapturedAt() throws Exception {
        PaymentIntent pi = pi("succeeded", 6450L, 0L);

        EscrowCaptureService.Outcome outcome;
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            stubRetrieve(mocked, pi);
            outcome = service.ensureCaptured(paymentId, "delivery");
        }
        verify(pi, never()).capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class));
        verify(paymentRepository).markCapturedIfEscrow(eq(paymentId), any());
        verify(paymentRepository).setStripeChargeIdIfMissing(paymentId, "ch_nego");
        assertThat(outcome.capturedNow()).isFalse();
        assertThat(outcome.chargeId()).isEqualTo("ch_nego");
        verifyNoInteractions(auditService);
        verify(alertEscalator).resolveOpen("ESCROW_CAPTURE_FAILED_" + paymentId);
    }

    @Test
    void succeeded_withCapturedAt_isANoOp() throws Exception {
        payment.setCapturedAt(Instant.now());
        payment.setStripeChargeId("ch_kept");
        PaymentIntent pi = pi("succeeded", 6450L, 0L);

        EscrowCaptureService.Outcome outcome;
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            stubRetrieve(mocked, pi);
            outcome = service.ensureCaptured(paymentId, "delivery");
        }
        verify(paymentRepository, never()).markCapturedIfEscrow(any(), any());
        verify(paymentRepository, never()).setStripeChargeIdIfMissing(any(), any());
        assertThat(outcome.chargeId()).isEqualTo("ch_kept");
    }

    @Test
    void canceledAuthorization_throwsAndAlertsWithoutCapture() throws Exception {
        PaymentIntent pi = pi("canceled", 6450L, 0L);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            stubRetrieve(mocked, pi);
            assertThatThrownBy(() -> service.ensureCaptured(paymentId, "delivery"))
                    .isInstanceOf(EscrowCaptureService.EscrowCaptureException.class)
                    .satisfies(e -> assertThat(((EscrowCaptureService.EscrowCaptureException) e).getPiStatus())
                            .isEqualTo("canceled"));
        }
        verify(pi, never()).capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class));
        verify(paymentRepository, never()).markCapturedIfEscrow(any(), any());
        verify(alertEscalator).raiseOnce(eq("ESCROW_CAPTURE_FAILED_" + paymentId), anyString(), anyMap());
        verify(alertEscalator, never()).resolveOpen(anyString());
    }

    @Test
    void paymentLeftEscrowConcurrently_isNeverCaptured() throws Exception {
        // Revue #487 : le listener lit requires_capture, un remboursement admin passe le paiement
        // REFUNDED (markRefundedIfEscrow) avant la garde : markCapturedIfEscrow répond 0. Sans
        // relecture, la capture partait quand même et encaissait un argent que la base dit rendu.
        PaymentIntent pi = pi("requires_capture", 6450L, 6450L);
        when(paymentRepository.markCapturedIfEscrow(eq(paymentId), any())).thenReturn(0);
        when(paymentRepository.lockIfEscrow(paymentId)).thenReturn(0);
        when(paymentRepository.findStatusById(paymentId)).thenReturn(Optional.of(PaymentStatus.REFUNDED));

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            stubRetrieve(mocked, pi);
            assertThatThrownBy(() -> service.ensureCaptured(paymentId, "negotiation-escrow-ready"))
                    .isInstanceOf(EscrowCaptureService.EscrowCaptureException.class)
                    .hasMessageContaining("plus en séquestre (REFUNDED)");
        }
        verify(pi, never()).capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class));
        verifyNoInteractions(auditService);
        // Course bénigne (aucun argent n'a bougé) : pas d'alerte.
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    void amountMismatch_neverCapturesPartially() throws Exception {
        PaymentIntent pi = pi("requires_capture", 9000L, 9000L);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            stubRetrieve(mocked, pi);
            assertThatThrownBy(() -> service.ensureCaptured(paymentId, "delivery"))
                    .isInstanceOf(EscrowCaptureService.EscrowCaptureException.class)
                    .hasMessageContaining("différent du montant attendu");
        }
        verify(pi, never()).capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class));
        verify(alertEscalator).raiseOnce(eq("ESCROW_CAPTURE_FAILED_" + paymentId), anyString(), anyMap());
    }

    @Test
    void stripeCaptureError_throwsWithCaptureBeforeInAlert() throws Exception {
        PaymentIntent pi = pi("requires_capture", 6450L, 6450L);
        Charge charge = mock(Charge.class, RETURNS_DEEP_STUBS);
        when(charge.getPaymentMethodDetails().getCard().getCaptureBefore()).thenReturn(1_791_000_000L);
        when(pi.getLatestChargeObject()).thenReturn(charge);
        when(pi.capture(any(PaymentIntentCaptureParams.class), any(RequestOptions.class)))
                .thenThrow(new InvalidRequestException("expired", null, "req_1", null, 400, null));
        when(paymentRepository.markCapturedIfEscrow(eq(paymentId), any())).thenReturn(1);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            stubRetrieve(mocked, pi);
            assertThatThrownBy(() -> service.ensureCaptured(paymentId, "admin-force-release"))
                    .isInstanceOf(EscrowCaptureService.EscrowCaptureException.class)
                    .hasMessageContaining("capture refusée par Stripe");
        }
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(alertEscalator).raiseOnce(eq("ESCROW_CAPTURE_FAILED_" + paymentId), detail.capture(), context.capture());
        assertThat(context.getValue()).containsEntry("captureBefore", Instant.ofEpochSecond(1_791_000_000L).toString())
                .containsEntry("source", "admin-force-release");
        assertThat(detail.getValue()).contains("capture possible jusqu'au");
        // Aucun enregistrement local : la transaction REQUIRES_NEW est annulée par l'exception.
        verify(paymentRepository, never()).save(any());
        verify(paymentRepository, never()).setStripeChargeIdIfMissing(any(), any());
        verifyNoInteractions(auditService);
    }

    @Test
    void retrieveError_throwsAndAlerts() {
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.retrieve(eq("pi_nego"), any(PaymentIntentRetrieveParams.class), isNull()))
                    .thenThrow(new InvalidRequestException("down", null, "req_2", null, 500, null));
            assertThatThrownBy(() -> service.ensureCaptured(paymentId, "delivery"))
                    .isInstanceOf(EscrowCaptureService.EscrowCaptureException.class)
                    .satisfies(e -> assertThat(((EscrowCaptureService.EscrowCaptureException) e).getPiStatus()).isNull());
        }
        verify(alertEscalator).raiseOnce(eq("ESCROW_CAPTURE_FAILED_" + paymentId), anyString(), anyMap());
    }

    @Test
    void alertFailure_doesNotHideTheCaptureFailure() {
        when(alertEscalator.raiseOnce(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("db"));
        PaymentIntent pi = pi("canceled", 6450L, 0L);
        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            stubRetrieve(mocked, pi);
            assertThatThrownBy(() -> service.ensureCaptured(paymentId, "delivery"))
                    .isInstanceOf(EscrowCaptureService.EscrowCaptureException.class);
        }
    }

    @Test
    void unknownPayment_isAnIllegalState() {
        UUID unknown = UUID.randomUUID();
        when(paymentRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.ensureCaptured(unknown, "delivery")).isInstanceOf(IllegalStateException.class);
    }
}
