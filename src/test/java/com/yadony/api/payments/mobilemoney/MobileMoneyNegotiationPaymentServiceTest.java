package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.requests.NegotiationMobileMoneyPort;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

@ExtendWith(MockitoExtension.class)
class MobileMoneyNegotiationPaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock PawapayProviderResolver providers;
    @Mock FirebaseContactService firebaseContact;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;
    @Mock PlatformTransactionManager transactionManager;

    MobileMoneyNegotiationPaymentService service;

    final UUID threadId = UUID.randomUUID();
    final UUID senderId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        PawapayProperties props = new PawapayProperties(true, "https://api.sandbox.pawapay.io", "token", true, 30,
                "https://api-staging.yadony.com", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting", new PawapayProperties.BalanceMin(null, null));
        service = new MobileMoneyNegotiationPaymentService(paymentRepository, userRepository, operations, submission,
                providers, firebaseContact, audit, events, transactionManager, props);
    }

    private static void setId(Object entity, UUID id) {
        try {
            var f = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private PaymentEntity payment(PaymentStatus status) {
        PaymentEntity p = new PaymentEntity();
        setId(p, UUID.randomUUID());
        p.setNegotiationThreadId(threadId);
        p.setRail(PaymentRail.PAWAPAY);
        p.setAmount(new BigDecimal("33000"));
        p.setCommissionAmount(new BigDecimal("3000"));
        p.setCurrency("XOF");
        p.setStatus(status);
        return p;
    }

    /** Jumeau du helper {@code op(...)} de {@link MobileMoneyBidPaymentServiceTest} : le constructeur
     * est la seule façon de poser {@code amount}, {@code PawapayOperationEntity} n'a pas de setter dessus. */
    private static PawapayOperationEntity operation(UUID paymentId, PawapayOperationStatus status, BigDecimal amount) {
        PawapayOperationEntity o = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT, paymentId,
                null, amount, "XOF", "ORANGE_SEN", "SN", "221771234567");
        o.setStatus(status);
        return o;
    }

    // ── createPendingPayment ─────────────────────────────────────────────

    @Test
    void createPendingPayment_newThread_createsPawapayPendingPayment_roundedForXof() {
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> { setId(inv.getArgument(0), UUID.randomUUID()); return inv.getArgument(0); });

        NegotiationMobileMoneyPort.PendingDeposit pending = service.createPendingPayment(
                threadId, senderId, travelerId, new BigDecimal("30000"), new BigDecimal("0.10"), "XOF");

        ArgumentCaptor<PaymentEntity> saved = ArgumentCaptor.forClass(PaymentEntity.class);
        verify(paymentRepository).save(saved.capture());
        assertThat(saved.getValue().getRail()).isEqualTo(PaymentRail.PAWAPAY);
        assertThat(saved.getValue().getNegotiationThreadId()).isEqualTo(threadId);
        assertThat(saved.getValue().getBidId()).isNull();
        assertThat(saved.getValue().getStripePaymentIntentId()).isNull();
        assertThat(saved.getValue().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(saved.getValue().getCommissionAmount()).isEqualByComparingTo("3000");
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("33000");
        assertThat(pending.gross()).isEqualByComparingTo("33000");
        assertThat(pending.expiresAt()).isAfter(LocalDateTime.now().plusMinutes(29));
    }

    @Test
    void createPendingPayment_existingPending_isReturnedUntouched() {
        PaymentEntity existing = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(existing));

        var pending = service.createPendingPayment(threadId, senderId, travelerId,
                new BigDecimal("30000"), new BigDecimal("0.10"), "XOF");

        assertThat(pending.paymentId()).isEqualTo(existing.getId());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void createPendingPayment_existingCancelled_isRecycledToPending() {
        PaymentEntity existing = payment(PaymentStatus.CANCELLED);
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(existing));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createPendingPayment(threadId, senderId, travelerId, new BigDecimal("30000"), new BigDecimal("0.10"), "XOF");

        assertThat(existing.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(existing.getRail()).isEqualTo(PaymentRail.PAWAPAY);
    }

    @Test
    void createPendingPayment_existingEscrow_isRefused409() {
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(payment(PaymentStatus.ESCROW)));

        assertThatThrownBy(() -> service.createPendingPayment(threadId, senderId, travelerId,
                new BigDecimal("30000"), new BigDecimal("0.10"), "XOF"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("negotiation-deposit-already-settled"));
    }

    @Test
    void createPendingPayment_existingStripePayment_isRefused409() {
        PaymentEntity card = payment(PaymentStatus.PENDING);
        card.setRail(PaymentRail.STRIPE);
        card.setStripePaymentIntentId("pi_123");
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.of(card));

        assertThatThrownBy(() -> service.createPendingPayment(threadId, senderId, travelerId,
                new BigDecimal("30000"), new BigDecimal("0.10"), "XOF"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("negotiation-card-escrow-in-flight"));
    }

    // ── releasePendingDeposit ────────────────────────────────────────────

    @Test
    void releasePendingDeposit_noPayment_isNothingPending() {
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.empty());
        assertThat(service.releasePendingDeposit(threadId)).isEqualTo(NegotiationMobileMoneyPort.ReleaseOutcome.NOTHING_PENDING);
    }

    @Test
    void releasePendingDeposit_openDeposit_waits() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        PawapayOperationEntity open = operation(p.getId(), PawapayOperationStatus.ACCEPTED, new BigDecimal("33000"));
        when(operations.findLatest(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(open));

        assertThat(service.releasePendingDeposit(threadId)).isEqualTo(NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_OPEN);
        verify(paymentRepository, never()).markCancelledIfPending(any());
    }

    @Test
    void releasePendingDeposit_completedNotApplied_isReported_neverCancelled() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        PawapayOperationEntity done = operation(p.getId(), PawapayOperationStatus.COMPLETED, new BigDecimal("33000"));
        when(operations.findLatest(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(done));

        assertThat(service.releasePendingDeposit(threadId)).isEqualTo(NegotiationMobileMoneyPort.ReleaseOutcome.DEPOSIT_COMPLETED_NOT_APPLIED);
        verify(paymentRepository, never()).markCancelledIfPending(any());
    }

    @Test
    void releasePendingDeposit_pendingWithoutOpenDeposit_isCancelled() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLatest(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(paymentRepository.markCancelledIfPending(p.getId())).thenReturn(1);

        assertThat(service.releasePendingDeposit(threadId)).isEqualTo(NegotiationMobileMoneyPort.ReleaseOutcome.CANCELLED);
        verify(audit).log(eq("PAYMENT"), eq(p.getId()), eq("NEGOTIATION_DEPOSIT_CANCELLED"), any(), any());
    }

    // ── refundEscrowedDeposit ────────────────────────────────────────────

    @Test
    void refundEscrowedDeposit_escrowed_submitsRefundOfTheDepositAmount() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(p.getId())).thenReturn(1);
        PawapayOperationEntity deposit = operation(p.getId(), PawapayOperationStatus.COMPLETED, new BigDecimal("33000"));
        when(operations.findLatest(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(deposit));
        PawapayOperationEntity refund = operation(p.getId(), PawapayOperationStatus.ACCEPTED, new BigDecimal("33000"));
        when(submission.submitRefund(p.getId(), deposit, new BigDecimal("33000"))).thenReturn(refund);

        assertThat(service.refundEscrowedDeposit(threadId)).isTrue();
        verify(submission).submitRefund(p.getId(), deposit, new BigDecimal("33000"));
    }

    @Test
    void refundEscrowedDeposit_notEscrowed_isNoop() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(paymentRepository.markRefundedIfEscrow(p.getId())).thenReturn(0);

        assertThat(service.refundEscrowedDeposit(threadId)).isFalse();
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    // ── initiateDeposit ──────────────────────────────────────────────────

    /** Même forme que le helper {@code resolved()} de {@code MobileMoneyBidPaymentServiceTest},
     * mais avec un {@link PawapayProviderConfig} construit réellement (record) plutôt que mocké. */
    private PawapayProviderResolver.Resolved resolved() {
        var limits = new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1000000"), null, null);
        var config = new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", limits, null);
        return new PawapayProviderResolver.Resolved("ORANGE_SEN", "SN", "221771234567", config);
    }

    @Test
    void initiateDeposit_pendingPayment_submitsDepositWithThreadReference() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(providers.resolve(eq("221771234567"), eq(PawapayOperationKind.DEPOSIT), eq("XOF"), any())).thenReturn(resolved());
        PawapayOperationEntity op = operation(p.getId(), PawapayOperationStatus.ACCEPTED, new BigDecimal("33000"));
        when(submission.submitDeposit(eq(p.getId()), eq("221771234567"), eq("ORANGE_SEN"), eq("SN"),
                eq(new BigDecimal("33000")), eq("XOF"), eq("thread-" + threadId), any(), any())).thenReturn(op);
        when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        var response = service.initiateDeposit(threadId, senderId, "+221 77 123 45 67", LocalDateTime.now().plusMinutes(20));

        assertThat(response.threadId()).isEqualTo(threadId);
        assertThat(response.paymentStatus()).isEqualTo("PENDING");
        assertThat(response.deposit().status()).isEqualTo("ACCEPTED");
        verify(audit).log(eq("PAYMENT"), eq(p.getId()), eq("NEGOTIATION_DEPOSIT_INITIATED"), eq(senderId), any());
    }

    @Test
    void initiateDeposit_liveDeposit_isReturnedWithoutSecondSubmission() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        PawapayOperationEntity live = operation(p.getId(), PawapayOperationStatus.ACCEPTED, new BigDecimal("33000"));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(live));

        service.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().plusMinutes(20));

        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void initiateDeposit_deadlinePassed_is422() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().minusMinutes(1)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-payment-expired"));
    }

    @Test
    void initiateDeposit_noPhoneAnywhere_is422() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(userRepository.findById(senderId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiateDeposit(threadId, senderId, null, LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-phone-required"));
    }

    // ── status ──────────────────────────────────────────────────────────

    @Test
    void status_withoutPayment_isEmptyView() {
        when(paymentRepository.findByNegotiationThreadId(threadId)).thenReturn(Optional.empty());
        var r = service.status(threadId, null);
        assertThat(r.paymentStatus()).isNull();
        assertThat(r.deposit()).isNull();
    }
}
