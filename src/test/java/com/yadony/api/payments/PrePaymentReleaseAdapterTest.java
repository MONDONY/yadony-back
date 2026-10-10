package com.yadony.api.payments;

import com.stripe.exception.InvalidRequestException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCancelParams;
import com.yadony.api.cancellation.PrePaymentReleasePort.Outcome;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrePaymentReleaseAdapterTest {

    @Mock PaymentRepository paymentRepository;
    @Mock StripeGateway stripeGateway;
    @Mock PawapayOperationService pawapayOperations;
    @Mock AuditService auditService;

    PrePaymentReleaseAdapter adapter;

    private final UUID bidId = UUID.randomUUID();
    private final UUID senderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adapter = new PrePaymentReleaseAdapter(paymentRepository, stripeGateway, pawapayOperations, auditService);
    }

    @Test
    void joinsTheCancellationTransaction() throws Exception {
        Transactional tx = PrePaymentReleaseAdapter.class
                .getMethod("releaseBeforeCancellation", UUID.class, String.class, UUID.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    // ── Carte ───────────────────────────────────────────────────────────────

    @Test
    void card_noPaymentNoIntent_nothingToRelease() {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.empty());

        assertThat(adapter.releaseBeforeCancellation(bidId, null, senderId)).isEqualTo(Outcome.NOTHING_TO_RELEASE);
        verify(paymentRepository, never()).markCancelledIfPending(any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"ESCROW", "RELEASED"})
    void card_paymentAlreadyHeld_alreadyPaid(PaymentStatus status) {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(card("pi_1", status)));

        assertThat(adapter.releaseBeforeCancellation(bidId, "pi_1", senderId)).isEqualTo(Outcome.ALREADY_PAID);
        verify(paymentRepository, never()).markCancelledIfPending(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"requires_payment_method", "requires_confirmation", "requires_action"})
    void card_unauthorizedIntent_isCancelledAndPaymentClosed(String piStatus) throws Exception {
        PaymentEntity payment = card("pi_1", PaymentStatus.PENDING);
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(payment));
        PaymentIntent pi = intent("pi_1", piStatus);
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(1);

        assertThat(adapter.releaseBeforeCancellation(bidId, "pi_1", senderId)).isEqualTo(Outcome.RELEASED);

        verify(pi).cancel(any(PaymentIntentCancelParams.class));
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq(PrePaymentReleaseAdapter.AUDIT_ACTION),
                eq(senderId), anyMap());
    }

    @ParameterizedTest
    @ValueSource(strings = {"requires_capture", "succeeded"})
    void card_authorizedIntent_neverCancelled(String piStatus) throws Exception {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(card("pi_1", PaymentStatus.PENDING)));
        PaymentIntent pi = intent("pi_1", piStatus);

        assertThat(adapter.releaseBeforeCancellation(bidId, "pi_1", senderId)).isEqualTo(Outcome.ALREADY_PAID);

        verify(pi, never()).cancel(any(PaymentIntentCancelParams.class));
        verify(paymentRepository, never()).markCancelledIfPending(any());
    }

    @Test
    void card_processingIntent_inProgress() throws Exception {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.empty());
        PaymentIntent pi = intent("pi_1", "processing");

        assertThat(adapter.releaseBeforeCancellation(bidId, "pi_1", senderId)).isEqualTo(Outcome.PAYMENT_IN_PROGRESS);
        verify(pi, never()).cancel(any(PaymentIntentCancelParams.class));
    }

    @Test
    void card_alreadyCanceledIntentAndClosedPayment_nothingToRelease() throws Exception {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(card("pi_1", PaymentStatus.CANCELLED)));
        PaymentIntent pi = intent("pi_1", "canceled");

        assertThat(adapter.releaseBeforeCancellation(bidId, null, senderId)).isEqualTo(Outcome.NOTHING_TO_RELEASE);
        verify(pi, never()).cancel(any(PaymentIntentCancelParams.class));
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    void card_canceledIntentButPendingPayment_closesThePayment() throws Exception {
        PaymentEntity payment = card("pi_1", PaymentStatus.PENDING);
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(payment));
        intent("pi_1", "canceled");
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(1);

        assertThat(adapter.releaseBeforeCancellation(bidId, "pi_1", senderId)).isEqualTo(Outcome.RELEASED);
    }

    @Test
    void card_secondIntentAuthorized_firstOneIsNotCancelled() throws Exception {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(card("pi_old", PaymentStatus.PENDING)));
        PaymentIntent stale = intent("pi_old", "requires_payment_method");
        intent("pi_new", "requires_capture");

        assertThat(adapter.releaseBeforeCancellation(bidId, "pi_new", senderId)).isEqualTo(Outcome.ALREADY_PAID);
        verify(stale, never()).cancel(any(PaymentIntentCancelParams.class));
    }

    @Test
    void card_cancelRace_reReadCanceled_released() throws Exception {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.empty());
        PaymentIntent first = mock(PaymentIntent.class);
        when(first.getStatus()).thenReturn("requires_action");
        when(first.getId()).thenReturn("pi_1");
        when(first.cancel(any(PaymentIntentCancelParams.class))).thenThrow(stripeError());
        PaymentIntent reread = mock(PaymentIntent.class);
        when(reread.getStatus()).thenReturn("canceled");
        when(stripeGateway.retrievePaymentIntent("pi_1")).thenReturn(first, reread);

        assertThat(adapter.releaseBeforeCancellation(bidId, "pi_1", senderId)).isEqualTo(Outcome.RELEASED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"requires_capture", "succeeded", "processing"})
    void card_cancelRace_reReadPaid_refused(String reread) throws Exception {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.empty());
        PaymentIntent first = mock(PaymentIntent.class);
        when(first.getStatus()).thenReturn("requires_action");
        when(first.getId()).thenReturn("pi_1");
        when(first.cancel(any(PaymentIntentCancelParams.class))).thenThrow(stripeError());
        PaymentIntent second = mock(PaymentIntent.class);
        when(second.getStatus()).thenReturn(reread);
        when(stripeGateway.retrievePaymentIntent("pi_1")).thenReturn(first, second);

        Outcome expected = "processing".equals(reread) ? Outcome.PAYMENT_IN_PROGRESS : Outcome.ALREADY_PAID;
        assertThat(adapter.releaseBeforeCancellation(bidId, "pi_1", senderId)).isEqualTo(expected);
    }

    @Test
    void card_cancelFailsForAnotherReason_badGateway() throws Exception {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.empty());
        PaymentIntent first = mock(PaymentIntent.class);
        when(first.getStatus()).thenReturn("requires_payment_method");
        when(first.getId()).thenReturn("pi_1");
        when(first.cancel(any(PaymentIntentCancelParams.class))).thenThrow(stripeError());
        when(stripeGateway.retrievePaymentIntent("pi_1")).thenReturn(first);

        assertThatThrownBy(() -> adapter.releaseBeforeCancellation(bidId, "pi_1", senderId))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test
    void card_stripeUnreachable_badGateway() throws Exception {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.empty());
        when(stripeGateway.retrievePaymentIntent("pi_1")).thenThrow(stripeError());

        assertThatThrownBy(() -> adapter.releaseBeforeCancellation(bidId, "pi_1", senderId))
                .isInstanceOf(YadonyBusinessException.class);
    }

    // ── Mobile money ────────────────────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"ESCROW", "RELEASED"})
    void mobileMoney_alreadyCollected_alreadyPaid(PaymentStatus status) {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(mobileMoney(status)));

        assertThat(adapter.releaseBeforeCancellation(bidId, null, senderId)).isEqualTo(Outcome.ALREADY_PAID);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CANCELLED", "FAILED", "REFUNDED"})
    void mobileMoney_terminal_nothingToRelease(PaymentStatus status) {
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(mobileMoney(status)));

        assertThat(adapter.releaseBeforeCancellation(bidId, null, senderId)).isEqualTo(Outcome.NOTHING_TO_RELEASE);
        org.mockito.Mockito.verifyNoInteractions(stripeGateway);
    }

    @ParameterizedTest
    @EnumSource(value = PawapayOperationStatus.class, names = {"CREATED", "ACCEPTED", "PROCESSING", "ENQUEUED",
            "IN_RECONCILIATION"})
    void mobileMoney_openDeposit_inProgress(PawapayOperationStatus depositStatus) {
        PaymentEntity payment = mobileMoney(PaymentStatus.PENDING);
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(payment));
        deposit(payment, depositStatus);

        assertThat(adapter.releaseBeforeCancellation(bidId, null, senderId)).isEqualTo(Outcome.PAYMENT_IN_PROGRESS);
        verify(paymentRepository, never()).markCancelledIfPending(any());
    }

    @Test
    void mobileMoney_completedDepositNotApplied_alreadyPaid() {
        PaymentEntity payment = mobileMoney(PaymentStatus.PENDING);
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(payment));
        deposit(payment, PawapayOperationStatus.COMPLETED);

        assertThat(adapter.releaseBeforeCancellation(bidId, null, senderId)).isEqualTo(Outcome.ALREADY_PAID);
        verify(paymentRepository, never()).markCancelledIfPending(any());
    }

    @Test
    void mobileMoney_failedDeposit_paymentClosed() {
        PaymentEntity payment = mobileMoney(PaymentStatus.PENDING);
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(payment));
        deposit(payment, PawapayOperationStatus.FAILED);
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(1);

        assertThat(adapter.releaseBeforeCancellation(bidId, null, senderId)).isEqualTo(Outcome.RELEASED);
        verify(auditService).log(eq("PAYMENT"), eq(payment.getId()), eq(PrePaymentReleaseAdapter.AUDIT_ACTION),
                eq(senderId), anyMap());
    }

    @Test
    void mobileMoney_noDeposit_lostTheClaim_alreadyPaid() {
        PaymentEntity payment = mobileMoney(PaymentStatus.PENDING);
        when(paymentRepository.findByBidIdForUpdate(bidId)).thenReturn(Optional.of(payment));
        when(pawapayOperations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(paymentRepository.markCancelledIfPending(payment.getId())).thenReturn(0);

        assertThat(adapter.releaseBeforeCancellation(bidId, null, senderId)).isEqualTo(Outcome.ALREADY_PAID);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private PaymentEntity card(String piId, PaymentStatus status) {
        PaymentEntity p = payment(status);
        p.setStripePaymentIntentId(piId);
        return p;
    }

    private PaymentEntity mobileMoney(PaymentStatus status) {
        PaymentEntity p = payment(status);
        p.setRail(PaymentRail.PAWAPAY);
        return p;
    }

    private PaymentEntity payment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setBidId(bidId);
        p.setAmount(new BigDecimal("42.00"));
        p.setStatus(status);
        return p;
    }

    private PaymentIntent intent(String id, String status) throws Exception {
        PaymentIntent pi = mock(PaymentIntent.class);
        org.mockito.Mockito.lenient().when(pi.getId()).thenReturn(id);
        when(pi.getStatus()).thenReturn(status);
        when(stripeGateway.retrievePaymentIntent(id)).thenReturn(pi);
        return pi;
    }

    private void deposit(PaymentEntity payment, PawapayOperationStatus status) {
        PawapayOperationEntity op = mock(PawapayOperationEntity.class);
        when(op.getStatus()).thenReturn(status);
        when(pawapayOperations.findLatest(payment.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(op));
    }

    private static InvalidRequestException stripeError() {
        return new InvalidRequestException("boom", null, "payment_intent_unexpected_state", null, 400, null);
    }
}
