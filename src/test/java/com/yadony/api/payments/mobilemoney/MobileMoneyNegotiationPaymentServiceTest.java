package com.yadony.api.payments.mobilemoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.PaymentEntity;
import com.yadony.api.payments.PaymentRail;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.PaymentStatus;
import com.yadony.api.payments.events.MobileMoneyNegotiationDepositConfirmedEvent;
import com.yadony.api.payments.events.MobileMoneyNegotiationDepositFailedEvent;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationService;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapayProperties;
import com.yadony.api.payments.pawapay.PawapayProviderResolver;
import com.yadony.api.payments.pawapay.PawapayProviders;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import com.yadony.api.payments.pawapay.dto.PawapayProviderPrediction;
import com.yadony.api.requests.NegotiationMobileMoneyPort;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
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

@ExtendWith(MockitoExtension.class)
class MobileMoneyNegotiationPaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock UserRepository userRepository;
    @Mock PawapayOperationService operations;
    @Mock PawapaySubmissionService submission;
    @Mock PawapayProviderResolver providers;
    @Mock PawapayClient client;
    @Mock FirebaseContactService firebaseContact;
    @Mock AuditService audit;
    @Mock ApplicationEventPublisher events;
    @Mock PlatformTransactionManager transactionManager;

    MobileMoneyNegotiationPaymentService service;
    PawapayProperties props;

    final UUID threadId = UUID.randomUUID();
    final UUID senderId = UUID.randomUUID();
    final UUID travelerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        props = new PawapayProperties(true, "https://api.sandbox.pawapay.io", "token", true, 30,
                "https://api-staging.yadony.com", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting", new PawapayProperties.BalanceMin(null, null));
        service = new MobileMoneyNegotiationPaymentService(paymentRepository, userRepository, operations, submission,
                providers, firebaseContact, audit, events, transactionManager, props);
    }

    /** Même props, avec un résolveur RÉEL adossé à {@link #client} mocké — seule façon de faire lever
     * {@code PawapayProviderResolver.UnsupportedNumberException} (constructeur package-private,
     * inaccessible depuis ce package) : jumeau de la construction de service dans
     * {@code MobileMoneyBidPaymentServiceTest}, qui procède de même. */
    private MobileMoneyNegotiationPaymentService serviceWithRealResolver() {
        return new MobileMoneyNegotiationPaymentService(paymentRepository, userRepository, operations, submission,
                new PawapayProviderResolver(client), firebaseContact, audit, events, transactionManager, props);
    }

    private MobileMoneyNegotiationPaymentService serviceWithProps(PawapayProperties p) {
        return new MobileMoneyNegotiationPaymentService(paymentRepository, userRepository, operations, submission,
                providers, firebaseContact, audit, events, transactionManager, p);
    }

    private static PawapayProperties disabledProps() {
        return new PawapayProperties(false, "https://api.sandbox.pawapay.io", "token", true, 30,
                "https://api-staging.yadony.com", "yadony://bids/%s/mobile-money/awaiting",
                "yadony://negotiations/%s/mobile-money/awaiting", new PawapayProperties.BalanceMin(null, null));
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

    // ── confirmEscrow / notifyDepositFailed ────────────────────────────────

    @Test
    void confirmEscrow_wins_publishesConfirmedEvent() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        UUID opId = UUID.randomUUID();
        when(paymentRepository.markEscrowIfPending(eq(p.getId()), any())).thenReturn(1);
        when(paymentRepository.findById(p.getId())).thenReturn(Optional.of(p));

        service.confirmEscrow(opId, p.getId());

        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOf(MobileMoneyNegotiationDepositConfirmedEvent.class);
        var e = (MobileMoneyNegotiationDepositConfirmedEvent) ev.getValue();
        assertThat(e.threadId()).isEqualTo(threadId);
        assertThat(e.operationId()).isEqualTo(opId);
        verify(audit).log(eq("PAYMENT"), eq(p.getId()), eq("NEGOTIATION_DEPOSIT_CONFIRMED"), any(), any());
    }

    @Test
    void confirmEscrow_replay_isSilent() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        when(paymentRepository.markEscrowIfPending(eq(p.getId()), any())).thenReturn(0);
        when(paymentRepository.findById(p.getId())).thenReturn(Optional.of(p));

        service.confirmEscrow(UUID.randomUUID(), p.getId());

        verify(events, never()).publishEvent(any());
        verify(submission, never()).submitRefund(any(), any(), any());
    }

    @Test
    void confirmEscrow_afterCancellation_refundsTheDeposit() {
        PaymentEntity p = payment(PaymentStatus.CANCELLED);
        UUID opId = UUID.randomUUID();
        when(paymentRepository.markEscrowIfPending(eq(p.getId()), any())).thenReturn(0);
        when(paymentRepository.findById(p.getId())).thenReturn(Optional.of(p));
        PawapayOperationEntity deposit = operation(p.getId(), PawapayOperationStatus.COMPLETED, new BigDecimal("33000"));
        when(operations.get(opId)).thenReturn(deposit);
        PawapayOperationEntity refund = operation(p.getId(), PawapayOperationStatus.ACCEPTED, new BigDecimal("33000"));
        when(submission.submitRefund(p.getId(), deposit, new BigDecimal("33000"))).thenReturn(refund);
        when(transactionManager.getTransaction(any())).thenReturn(org.mockito.Mockito.mock(org.springframework.transaction.TransactionStatus.class));

        service.confirmEscrow(opId, p.getId());

        verify(submission).submitRefund(p.getId(), deposit, new BigDecimal("33000"));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void notifyDepositFailed_publishesFailedEvent_paymentStaysPending() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findById(p.getId())).thenReturn(Optional.of(p));

        service.notifyDepositFailed(UUID.randomUUID(), p.getId(), "PAYER_LIMIT_REACHED");

        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(ev.capture());
        assertThat(ev.getValue()).isInstanceOf(MobileMoneyNegotiationDepositFailedEvent.class);
        verify(paymentRepository, never()).markCancelledIfPending(any());
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

    // ── initiateDeposit — introuvable / statut du paiement ────────────────

    @Test
    void initiateDeposit_noPayment_is404() {
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiateDeposit(threadId, senderId, null, LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-payment-not-found"));
    }

    @Test
    void initiateDeposit_paymentNotPending_is409() {
        PaymentEntity p = payment(PaymentStatus.ESCROW);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.initiateDeposit(threadId, senderId, null, LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-payment-not-pending"));
    }

    // ── initiateDeposit — interrupteur d'urgence ───────────────────────────

    /** Jumeau de {@code MobileMoneyBidPaymentServiceTest#initiateDeposit_railDisabled_blocksOnlyANewSubmission} :
     * la garde est placée APRÈS la branche idempotente (aucun deposit vivant ici), elle bloque donc
     * toute NOUVELLE soumission. */
    @Test
    void initiateDeposit_railDisabled_blocksOnlyANewSubmission() {
        MobileMoneyNegotiationPaymentService svc = serviceWithProps(disabledProps());
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-disabled"));
        verifyNoInteractions(submission, providers);
    }

    /** Jumeau de {@code MobileMoneyBidPaymentServiceTest#initiateDeposit_railDisabled_stillReturnsAnAlreadyLiveDeposit} :
     * relire une opération déjà en vol n'engage aucun débit, l'interrupteur d'urgence ne doit donc
     * jamais bloquer ce chemin. */
    @Test
    void initiateDeposit_railDisabled_stillReturnsAnAlreadyLiveDeposit() {
        MobileMoneyNegotiationPaymentService svc = serviceWithProps(disabledProps());
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        PawapayOperationEntity live = operation(p.getId(), PawapayOperationStatus.ACCEPTED, new BigDecimal("33000"));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.of(live));

        var r = svc.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().plusMinutes(20));

        assertThat(r.deposit().status()).isEqualTo("ACCEPTED");
        verifyNoInteractions(submission, providers);
    }

    // ── initiateDeposit — résolution du payeur (PawapayProviderResolver réel) ─────────────────

    @Test
    void initiateDeposit_noProviderForNumber_is422_withoutReadingTheConfiguration() {
        MobileMoneyNegotiationPaymentService svc = serviceWithRealResolver();
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException b = (YadonyBusinessException) e;
                    assertThat(b.getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
                    assertThat(b.getMessage()).contains("Aucun opérateur");
                });
        verify(client, never()).activeConfiguration();
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void initiateDeposit_providerClosedForDeposits_is422_namingTheProvider() {
        MobileMoneyNegotiationPaymentService svc = serviceWithRealResolver();
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("SEN", "ORANGE_SEN", "221771234567")));
        var closed = new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "PROVIDER_AUTH", "CLOSED");
        var ok = new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "PROVIDER_AUTH", "OPERATIONAL");
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", closed, ok)));

        assertThatThrownBy(() -> svc.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException b = (YadonyBusinessException) e;
                    assertThat(b.getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
                    assertThat(b.getMessage()).contains("Orange Money");
                });
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void initiateDeposit_currencyMismatch_is422() {
        MobileMoneyNegotiationPaymentService svc = serviceWithRealResolver();
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("CMR", "MTN_MOMO_CMR", "221771234567")));
        var ok = new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "PROVIDER_AUTH", "OPERATIONAL");
        when(client.activeConfiguration()).thenReturn(Map.of("MTN_MOMO_CMR", new PawapayProviderConfig("MTN_MOMO_CMR", "CMR", "XAF", ok, ok)));

        assertThatThrownBy(() -> svc.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException b = (YadonyBusinessException) e;
                    assertThat(b.getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
                    assertThat(b.getMessage()).contains("XAF");
                });
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void initiateDeposit_unmappableCountry_is422() {
        MobileMoneyNegotiationPaymentService svc = serviceWithRealResolver();
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        // "ZZZ" n'est un alpha-3 ISO d'aucun pays réel : PawapayCountries.toAlpha2 renvoie null.
        when(client.predictProvider("221771234567")).thenReturn(Optional.of(new PawapayProviderPrediction("ZZZ", "ORANGE_SEN", "221771234567")));
        var ok = new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), "PROVIDER_AUTH", "OPERATIONAL");
        when(client.activeConfiguration()).thenReturn(Map.of("ORANGE_SEN", new PawapayProviderConfig("ORANGE_SEN", "ZZZ", "XOF", ok, ok)));

        assertThatThrownBy(() -> svc.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-payer-unsupported"));
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ── initiateDeposit — limites de montant, redirection Wave ─────────────

    @Test
    void initiateDeposit_amountAboveProviderDepositCap_is422() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        // Plafond deposit de l'opérateur (10 000) sous le montant du paiement (33 000).
        var capped = new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("10000"), null, null);
        var config = new PawapayProviderConfig("ORANGE_SEN", "SEN", "XOF", capped, null);
        when(providers.resolve(eq("221771234567"), eq(PawapayOperationKind.DEPOSIT), eq("XOF"), any()))
                .thenReturn(new PawapayProviderResolver.Resolved("ORANGE_SEN", "SN", "221771234567", config));

        assertThatThrownBy(() -> service.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException b = (YadonyBusinessException) e;
                    assertThat(b.getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
                    assertThat(b.getMessage()).contains("limites");
                });
        verify(submission, never()).submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    /** {@code isRedirectDeposit()} vrai (Wave) : les URL de retour sont construites sur le threadId,
     * jamais sur un bidId. */
    @Test
    void initiateDeposit_wave_passesReturnUrlsBuiltOnThreadId() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        var redirect = new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1500000"), PawapayProviders.REDIRECT_AUTH, "OPERATIONAL");
        var config = new PawapayProviderConfig("WAVE_SEN", "SEN", "XOF", redirect, null);
        when(providers.resolve(eq("221771234567"), eq(PawapayOperationKind.DEPOSIT), eq("XOF"), any()))
                .thenReturn(new PawapayProviderResolver.Resolved("WAVE_SEN", "SN", "221771234567", config));
        when(submission.submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(operation(p.getId(), PawapayOperationStatus.ACCEPTED, new BigDecimal("33000")));

        service.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().plusMinutes(20));

        verify(submission).submitDeposit(eq(p.getId()), eq("221771234567"), eq("WAVE_SEN"), eq("SN"), eq(new BigDecimal("33000")), eq("XOF"),
                eq("thread-" + threadId),
                eq("https://api-staging.yadony.com/api/v1/pawapay/return/thread/" + threadId + "?outcome=success"),
                eq("https://api-staging.yadony.com/api/v1/pawapay/return/thread/" + threadId + "?outcome=failed"));
    }

    // ── initiateDeposit — numéro invalide (override, Firebase) ─────────────

    @Test
    void initiateDeposit_invalidOverrideNumber_is422_beforeAnyPawapayCall() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiateDeposit(threadId, senderId, "pas un numéro", LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException b = (YadonyBusinessException) e;
                    assertThat(b.getErrorCode()).isEqualTo("mobile-money-payer-unsupported");
                    assertThat(b.getMessage()).contains("invalide");
                });
        verifyNoInteractions(providers);
    }

    @Test
    void initiateDeposit_firebasePhoneInvalid_is422() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        UserEntity sender = new UserEntity();
        sender.setFirebaseUid("s-uid");
        when(userRepository.findById(senderId)).thenReturn(Optional.of(sender));
        when(firebaseContact.getContact("s-uid")).thenReturn(new FirebaseContactService.Contact("12", null));

        assertThatThrownBy(() -> service.initiateDeposit(threadId, senderId, null, LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-payer-unsupported"));
        verifyNoInteractions(providers);
    }

    // ── initiateDeposit — refus pawaPay ────────────────────────────────────

    @Test
    void initiateDeposit_submitRejected_is422WithReason() {
        PaymentEntity p = payment(PaymentStatus.PENDING);
        when(paymentRepository.findByNegotiationThreadIdForUpdate(threadId)).thenReturn(Optional.of(p));
        when(operations.findLive(p.getId(), PawapayOperationKind.DEPOSIT)).thenReturn(Optional.empty());
        when(providers.resolve(eq("221771234567"), eq(PawapayOperationKind.DEPOSIT), eq("XOF"), any())).thenReturn(resolved());
        PawapayOperationEntity rejected = operation(p.getId(), PawapayOperationStatus.SUBMIT_REJECTED, new BigDecimal("33000"));
        rejected.setFailureMessage("Provider down");
        when(submission.submitDeposit(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(rejected);

        assertThatThrownBy(() -> service.initiateDeposit(threadId, senderId, "+221771234567", LocalDateTime.now().plusMinutes(20)))
                .isInstanceOf(YadonyBusinessException.class)
                .hasMessageContaining("Provider down")
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-deposit-rejected"));
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
