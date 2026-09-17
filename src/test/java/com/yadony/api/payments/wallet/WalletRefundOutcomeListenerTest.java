package com.yadony.api.payments.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.events.PawapayOperationCompletedEvent;
import com.yadony.api.payments.pawapay.events.PawapayOperationFailedEvent;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Écouteur branché sur un vrai {@link WalletPawapayRefundIssuer} (dépendances doublées). */
class WalletRefundOutcomeListenerTest {

    static final UUID USER_ID = UUID.randomUUID();

    final PawapayOperationRepository operationRepository = mock(PawapayOperationRepository.class);
    final PawapaySubmissionService submission = mock(PawapaySubmissionService.class);
    final WalletRefundRequestItemRepository itemRepository = mock(WalletRefundRequestItemRepository.class);
    final AuditService auditService = mock(AuditService.class);
    final AdminAlertService adminAlertService = mock(AdminAlertService.class);
    final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    final WalletSelfRefundService selfRefund = mock(WalletSelfRefundService.class);
    final WalletPawapayRefundIssuer issuer = new WalletPawapayRefundIssuer(operationRepository, submission,
            mock(PawapayClient.class), itemRepository, auditService, adminAlertService, eventPublisher);
    final WalletRefundOutcomeListener listener = new WalletRefundOutcomeListener(issuer, selfRefund);

    PawapayOperationEntity deposit;
    WalletRefundRequestItemEntity item;

    @BeforeEach
    void setUp() {
        deposit = operation(PawapayOperationKind.DEPOSIT, PawapayOperationPurpose.WALLET_TOPUP);
        item = new WalletRefundRequestItemEntity();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        item.setRefundRequestId(UUID.randomUUID());
        item.setPaymentIntentId("pawapay:" + deposit.getId());
        item.setAmount(new BigDecimal("10000.00"));
        item.setFeeAmount(new BigDecimal("200.00"));
        item.setStatus(WalletRefundItemStatus.PROCESSING);
    }

    static PawapayOperationEntity operation(PawapayOperationKind kind, PawapayOperationPurpose purpose) {
        return new PawapayOperationEntity(UUID.randomUUID(), kind, purpose, USER_ID, null, null,
                new BigDecimal("9800"), "XOF", "ORANGE_CIV", "CI", "+2250734567890");
    }

    static PawapayOperationCompletedEvent completed(UUID opId, PawapayOperationKind kind, PawapayOperationPurpose purpose) {
        return new PawapayOperationCompletedEvent(opId, kind, purpose, null, USER_ID);
    }

    static PawapayOperationFailedEvent failed(UUID opId, PawapayOperationKind kind, String code) {
        return new PawapayOperationFailedEvent(opId, kind, PawapayOperationPurpose.WALLET_REFUND, null, USER_ID, code, "msg");
    }

    @Test
    void refundCompleted_marksItemRefundedAndResolves() {
        UUID refundId = UUID.randomUUID();
        item.setPawapayRefundId(refundId);
        when(itemRepository.findByPawapayRefundId(refundId)).thenReturn(Optional.of(item));

        listener.onCompleted(completed(refundId, PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND));

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
        verify(itemRepository).save(item);
        verify(selfRefund).resolveIfComplete(item.getRefundRequestId());
    }

    @Test
    void refundFailed_fallsBackToPayout() {
        UUID refundId = UUID.randomUUID();
        item.setPawapayRefundId(refundId);
        when(itemRepository.findByPawapayRefundId(refundId)).thenReturn(Optional.of(item));
        when(operationRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        PawapayOperationEntity payout = operation(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND);
        when(submission.createWalletPayout(eq(USER_ID), eq(deposit.getMsisdn()), eq("ORANGE_CIV"), eq("CI"), any(),
                eq("XOF"))).thenReturn(payout);

        listener.onFailed(failed(refundId, PawapayOperationKind.REFUND, "REFUND_FAILED"));

        assertThat(item.getPawapayPayoutId()).isEqualTo(payout.getId());
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        verify(auditService).log(eq("wallet_refund_request"), eq(item.getRefundRequestId()),
                eq("REFUND_FALLBACK_PAYOUT"), eq(USER_ID), anyMap());
        verify(eventPublisher).publishEvent(new WalletPawapayRefundInitiationEvent(item.getId(), payout.getId()));
        verify(selfRefund).resolveIfComplete(item.getRefundRequestId());
    }

    @Test
    void refundFailedReplay_afterFallback_doesNotRelaunchPayout() {
        UUID refundId = UUID.randomUUID();
        item.setPawapayRefundId(refundId);
        item.setPawapayPayoutId(UUID.randomUUID());
        when(itemRepository.findByPawapayRefundId(refundId)).thenReturn(Optional.of(item));

        listener.onFailed(failed(refundId, PawapayOperationKind.REFUND, "REFUND_FAILED"));

        verify(submission, never()).createWalletPayout(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(auditService, eventPublisher);
    }

    @Test
    void payoutCompleted_marksItemRefunded() {
        UUID payoutId = UUID.randomUUID();
        item.setPawapayPayoutId(payoutId);
        when(itemRepository.findByPawapayPayoutId(payoutId)).thenReturn(Optional.of(item));

        listener.onCompleted(completed(payoutId, PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND));

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
        verify(selfRefund).resolveIfComplete(item.getRefundRequestId());
    }

    @Test
    void payoutCompletedReplay_onRefundedItem_changesNothing() {
        UUID payoutId = UUID.randomUUID();
        item.setPawapayPayoutId(payoutId);
        item.setStatus(WalletRefundItemStatus.REFUNDED);
        when(itemRepository.findByPawapayPayoutId(payoutId)).thenReturn(Optional.of(item));

        listener.onCompleted(completed(payoutId, PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND));

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
        verify(itemRepository, never()).save(any());
    }

    @Test
    void payoutFailed_marksItemFailedWithCodeAndAlerts() {
        UUID payoutId = UUID.randomUUID();
        item.setPawapayPayoutId(payoutId);
        when(itemRepository.findByPawapayPayoutId(payoutId)).thenReturn(Optional.of(item));

        listener.onFailed(failed(payoutId, PawapayOperationKind.PAYOUT, "RECIPIENT_NOT_FOUND"));

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
        assertThat(item.getFailureReason()).isEqualTo("RECIPIENT_NOT_FOUND");
        verify(adminAlertService).raise(eq("wallet-self-refund-failed"), anyString(), anyMap());
        verify(selfRefund).resolveIfComplete(item.getRefundRequestId());
    }

    @Test
    void payoutFailedReplay_onFailedItem_doesNotAlertTwice() {
        UUID payoutId = UUID.randomUUID();
        item.setPawapayPayoutId(payoutId);
        item.setStatus(WalletRefundItemStatus.FAILED);
        when(itemRepository.findByPawapayPayoutId(payoutId)).thenReturn(Optional.of(item));

        listener.onFailed(failed(payoutId, PawapayOperationKind.PAYOUT, "RECIPIENT_NOT_FOUND"));

        verifyNoInteractions(adminAlertService);
    }

    @Test
    void bidPurposeEvents_ignored() {
        UUID opId = UUID.randomUUID();

        listener.onCompleted(completed(opId, PawapayOperationKind.REFUND, PawapayOperationPurpose.BID_PAYMENT));
        listener.onCompleted(completed(opId, PawapayOperationKind.DEPOSIT, PawapayOperationPurpose.WALLET_TOPUP));
        listener.onFailed(new PawapayOperationFailedEvent(opId, PawapayOperationKind.PAYOUT,
                PawapayOperationPurpose.BID_PAYMENT, UUID.randomUUID(), null, "X", "m"));
        listener.onCompleted(completed(opId, PawapayOperationKind.DEPOSIT, PawapayOperationPurpose.WALLET_REFUND));

        verifyNoInteractions(itemRepository, selfRefund, submission);
    }

    @Test
    void outcomeForUnlinkedOperation_isIgnored() {
        UUID refundId = UUID.randomUUID();
        when(itemRepository.findByPawapayRefundId(refundId)).thenReturn(Optional.empty());

        listener.onCompleted(completed(refundId, PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND));

        verifyNoInteractions(selfRefund);
    }

    @Test
    void initiationRequested_accepted_doesNotResolve() {
        PawapayOperationEntity refund = operation(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND);
        when(operationRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(submission.initiate(refund, "wallet-refund-" + item.getId())).thenReturn(PawapayInitiationResult.accepted());

        listener.onInitiationRequested(new WalletPawapayRefundInitiationEvent(item.getId(), refund.getId()));

        verify(submission).initiate(refund, "wallet-refund-" + item.getId());
        verifyNoInteractions(selfRefund);
    }

    @Test
    void initiationRequested_payoutRejected_failsItemAndResolves() {
        PawapayOperationEntity payout = operation(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND);
        item.setPawapayPayoutId(payout.getId());
        when(operationRepository.findById(payout.getId())).thenReturn(Optional.of(payout));
        when(submission.initiate(any(), any())).thenReturn(new PawapayInitiationResult(
                PawapayInitiationResult.Outcome.REJECTED, "INVALID_AMOUNT", "m"));
        when(itemRepository.findByPawapayPayoutId(payout.getId())).thenReturn(Optional.of(item));

        listener.onInitiationRequested(new WalletPawapayRefundInitiationEvent(item.getId(), payout.getId()));

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
        verify(selfRefund).resolveIfComplete(item.getRefundRequestId());
    }

    @Test
    void everyHandler_isAfterCommitInItsOwnTransaction() throws Exception {
        for (Method m : new Method[] {
                WalletRefundOutcomeListener.class.getMethod("onInitiationRequested", WalletPawapayRefundInitiationEvent.class),
                WalletRefundOutcomeListener.class.getMethod("onCompleted", PawapayOperationCompletedEvent.class),
                WalletRefundOutcomeListener.class.getMethod("onFailed", PawapayOperationFailedEvent.class)}) {
            assertThat(m.getAnnotation(TransactionalEventListener.class).phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
            assertThat(m.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        }
    }
}
