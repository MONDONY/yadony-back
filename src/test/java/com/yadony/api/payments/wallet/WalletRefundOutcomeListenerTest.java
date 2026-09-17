package com.yadony.api.payments.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
    final WalletRefundRequestRepository requestRepository = mock(WalletRefundRequestRepository.class);
    final WalletRefundOutcomeListener listener = new WalletRefundOutcomeListener(issuer, selfRefund,
            requestRepository, itemRepository, adminAlertService);

    PawapayOperationEntity deposit;
    WalletRefundRequestItemEntity item;
    WalletRefundRequestEntity request;

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
        request = new WalletRefundRequestEntity();
        ReflectionTestUtils.setField(request, "id", item.getRefundRequestId());
        request.setUserId(USER_ID);
        request.setStatus(WalletRefundRequestStatus.PROCESSING);
    }

    /** L'item est retrouvé par son opération, sa demande verrouillée. */
    void linkRequest(UUID operationId) {
        when(itemRepository.findRefundRequestIdByPawapayOperationId(operationId))
                .thenReturn(Optional.of(item.getRefundRequestId()));
        when(requestRepository.findByIdForUpdate(item.getRefundRequestId())).thenReturn(Optional.of(request));
    }

    static PawapayOperationEntity operation(PawapayOperationKind kind, PawapayOperationPurpose purpose) {
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), kind, purpose, USER_ID, null, null,
                new BigDecimal("9800"), "XOF", "ORANGE_CIV", "CI", "+2250734567890");
        ReflectionTestUtils.setField(op, "createdAt", LocalDateTime.now(ZoneOffset.UTC));
        return op;
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
        linkRequest(refundId);

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
        linkRequest(refundId);
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
        linkRequest(refundId);

        listener.onFailed(failed(refundId, PawapayOperationKind.REFUND, "REFUND_FAILED"));

        verify(submission, never()).createWalletPayout(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(auditService, eventPublisher);
    }

    @Test
    void payoutCompleted_marksItemRefunded() {
        UUID payoutId = UUID.randomUUID();
        item.setPawapayPayoutId(payoutId);
        when(itemRepository.findByPawapayPayoutId(payoutId)).thenReturn(Optional.of(item));
        linkRequest(payoutId);

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
        linkRequest(payoutId);

        listener.onCompleted(completed(payoutId, PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND));

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
        verify(itemRepository, never()).save(any());
    }

    @Test
    void payoutFailed_marksItemFailedWithCodeAndAlerts() {
        UUID payoutId = UUID.randomUUID();
        item.setPawapayPayoutId(payoutId);
        when(itemRepository.findByPawapayPayoutId(payoutId)).thenReturn(Optional.of(item));
        linkRequest(payoutId);

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
        linkRequest(payoutId);

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
        when(itemRepository.findRefundRequestIdByPawapayOperationId(refundId)).thenReturn(Optional.empty());

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
        linkRequest(payout.getId());

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

    @Test
    void settle_locksRequestBeforeItem() {
        UUID payoutId = UUID.randomUUID();
        item.setPawapayPayoutId(payoutId);
        when(itemRepository.findByPawapayPayoutId(payoutId)).thenReturn(Optional.of(item));
        linkRequest(payoutId);

        listener.onCompleted(completed(payoutId, PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND));

        InOrder order = inOrder(itemRepository, requestRepository, selfRefund);
        order.verify(itemRepository).findRefundRequestIdByPawapayOperationId(payoutId);
        order.verify(requestRepository).findByIdForUpdate(item.getRefundRequestId());
        order.verify(itemRepository).findByPawapayPayoutId(payoutId);
        order.verify(selfRefund).resolveIfComplete(item.getRefundRequestId());
    }

    @Test
    void settle_requestMissing_doesNothing() {
        UUID payoutId = UUID.randomUUID();
        when(itemRepository.findRefundRequestIdByPawapayOperationId(payoutId))
                .thenReturn(Optional.of(item.getRefundRequestId()));
        when(requestRepository.findByIdForUpdate(item.getRefundRequestId())).thenReturn(Optional.empty());

        listener.onCompleted(completed(payoutId, PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND));

        verify(itemRepository, never()).findByPawapayPayoutId(any());
        verifyNoInteractions(selfRefund);
    }

    @Test
    void settle_itemGoneAfterLock_doesNothing() {
        UUID payoutId = UUID.randomUUID();
        linkRequest(payoutId);
        when(itemRepository.findByPawapayPayoutId(payoutId)).thenReturn(Optional.empty());

        listener.onCompleted(completed(payoutId, PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND));

        verifyNoInteractions(selfRefund);
    }

    @Test
    void completed_exceptionInSettle_alertsThenRethrows() {
        UUID payoutId = UUID.randomUUID();
        item.setPawapayPayoutId(payoutId);
        when(itemRepository.findByPawapayPayoutId(payoutId)).thenReturn(Optional.of(item));
        linkRequest(payoutId);
        doThrow(new IllegalStateException("debit impossible")).when(selfRefund).resolveIfComplete(any());

        assertThatThrownBy(() -> listener.onCompleted(
                completed(payoutId, PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND)))
                .hasMessage("debit impossible");

        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(adminAlertService).raise(eq("WALLET_REFUND_OUTCOME_FAILED"), anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("operationId", payoutId.toString());
    }

    @Test
    void failed_exceptionInFallback_alertsThenRethrows() {
        UUID refundId = UUID.randomUUID();
        item.setPawapayRefundId(refundId);
        when(itemRepository.findByPawapayRefundId(refundId)).thenReturn(Optional.of(item));
        linkRequest(refundId);
        when(operationRepository.findById(deposit.getId())).thenThrow(new IllegalStateException("base"));

        assertThatThrownBy(() -> listener.onFailed(failed(refundId, PawapayOperationKind.REFUND, "X")))
                .isInstanceOf(IllegalStateException.class);

        verify(adminAlertService).raise(eq("WALLET_REFUND_OUTCOME_FAILED"), anyString(), anyMap());
    }

    @Test
    void initiation_exception_alertsWithItemThenRethrows() {
        UUID opId = UUID.randomUUID();
        when(operationRepository.findById(opId)).thenThrow(new IllegalStateException("base"));

        assertThatThrownBy(() -> listener.onInitiationRequested(new WalletPawapayRefundInitiationEvent(item.getId(), opId)))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(adminAlertService).raise(eq("WALLET_REFUND_OUTCOME_FAILED"), anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("itemId", item.getId().toString())
                .containsEntry("operationId", opId.toString());
    }

    @Test
    void initiationRequested_refundRejected_locksRequestThenFallsBackToPayout() {
        PawapayOperationEntity refund = operation(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND);
        item.setPawapayRefundId(refund.getId());
        when(operationRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(operationRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));
        when(submission.initiate(any(), any())).thenReturn(new PawapayInitiationResult(
                PawapayInitiationResult.Outcome.REJECTED, "REFUND_NOT_ALLOWED", "m"));
        when(itemRepository.findByPawapayRefundId(refund.getId())).thenReturn(Optional.of(item));
        linkRequest(refund.getId());
        PawapayOperationEntity payout = operation(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND);
        when(submission.createWalletPayout(eq(USER_ID), any(), any(), any(), any(), any())).thenReturn(payout);

        listener.onInitiationRequested(new WalletPawapayRefundInitiationEvent(item.getId(), refund.getId()));

        InOrder order = inOrder(requestRepository, itemRepository);
        order.verify(requestRepository).findByIdForUpdate(item.getRefundRequestId());
        order.verify(itemRepository).findByPawapayRefundId(refund.getId());
        assertThat(item.getPawapayPayoutId()).isEqualTo(payout.getId());
        verify(selfRefund).resolveIfComplete(item.getRefundRequestId());
    }
}
