package com.yadony.api.payments.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.pawapay.PawapayClient;
import com.yadony.api.payments.pawapay.PawapayErrors;
import com.yadony.api.payments.pawapay.PawapayOperationEntity;
import com.yadony.api.payments.pawapay.PawapayOperationKind;
import com.yadony.api.payments.pawapay.PawapayOperationPurpose;
import com.yadony.api.payments.pawapay.PawapayOperationRepository;
import com.yadony.api.payments.pawapay.PawapayOperationStatus;
import com.yadony.api.payments.pawapay.PawapaySubmissionService;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayProviderConfig;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;

@ExtendWith(MockitoExtension.class)
class WalletPawapayRefundIssuerTest {

    static final UUID USER_ID = UUID.randomUUID();
    static final String PROVIDER = "ORANGE_CIV";
    static final PawapayProviderConfig.Limits OPEN =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1000000"), null, "OPERATIONAL");
    static final PawapayProviderConfig.Limits CLOSED =
            new PawapayProviderConfig.Limits(new BigDecimal("100"), new BigDecimal("1000000"), null, "CLOSED");

    @Mock PawapayOperationRepository operationRepository;
    @Mock PawapaySubmissionService submission;
    @Mock PawapayClient client;
    @Mock WalletRefundRequestItemRepository itemRepository;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlertService;
    @Mock ApplicationEventPublisher eventPublisher;

    WalletPawapayRefundIssuer issuer;
    PawapayOperationEntity deposit;
    WalletRefundRequestEntity request;

    @BeforeEach
    void setUp() {
        issuer = new WalletPawapayRefundIssuer(operationRepository, submission, client, itemRepository, auditService,
                adminAlertService, eventPublisher);
        deposit = operation(PawapayOperationKind.DEPOSIT, PawapayOperationPurpose.WALLET_TOPUP, new BigDecimal("10000"));
        request = new WalletRefundRequestEntity();
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        request.setUserId(USER_ID);
        request.setCurrency("XOF");
        request.setChannel(WalletRefundChannel.AUTOMATIC_PAWAPAY);
    }

    static PawapayOperationEntity operation(PawapayOperationKind kind, PawapayOperationPurpose purpose,
                                            BigDecimal amount) {
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), kind, purpose, USER_ID, null, null,
                amount, "XOF", PROVIDER, "CI", "+2250734567890");
        ReflectionTestUtils.setField(op, "createdAt", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(5));
        return op;
    }

    WalletRefundRequestItemEntity item() {
        WalletRefundRequestItemEntity item = new WalletRefundRequestItemEntity();
        ReflectionTestUtils.setField(item, "id", UUID.randomUUID());
        item.setRefundRequestId(request.getId());
        item.setWalletTransactionId(UUID.randomUUID());
        item.setPaymentIntentId("pawapay:" + deposit.getId());
        item.setAmount(new BigDecimal("10000.00"));
        item.setFeeAmount(new BigDecimal("200.00"));
        item.setStatus(WalletRefundItemStatus.PENDING);
        return item;
    }

    void stubDeposit() {
        when(operationRepository.findById(deposit.getId())).thenReturn(Optional.of(deposit));
    }

    void stubConfig(PawapayProviderConfig.Limits refund) {
        when(client.activeConfiguration()).thenReturn(Map.of(PROVIDER,
                new PawapayProviderConfig(PROVIDER, "CIV", "XOF", OPEN, OPEN, refund)));
    }

    // ── issue ────────────────────────────────────────────────────────────────

    @Test
    void issue_providerSupportsRefund_submitsPartialRefundOfNetAmount() {
        WalletRefundRequestItemEntity item = item();
        stubDeposit();
        stubConfig(OPEN);
        PawapayOperationEntity refund = operation(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        when(submission.createWalletRefund(eq(USER_ID), eq(deposit), any())).thenReturn(refund);

        issuer.issue(request, List.of(item));

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(submission).createWalletRefund(eq(USER_ID), eq(deposit), amount.capture());
        assertThat(amount.getValue()).isEqualByComparingTo("9800");
        assertThat(item.getPawapayRefundId()).isEqualTo(refund.getId());
        assertThat(item.getPawapayPayoutId()).isNull();
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        verify(itemRepository).save(item);
        verify(submission, never()).createWalletPayout(any(), any(), any(), any(), any(), any());
    }

    @Test
    void issue_linkIsSavedBeforeInitiationIsRequested_andNoNetworkCallInTheTransaction() {
        // R11/R12 : l'identifiant est posé et sauvegardé, puis l'initiation n'est que DEMANDÉE
        // (événement traité après commit), jamais appelée dans la transaction de l'émission.
        WalletRefundRequestItemEntity item = item();
        stubDeposit();
        stubConfig(OPEN);
        PawapayOperationEntity refund = operation(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        when(submission.createWalletRefund(any(), any(), any())).thenReturn(refund);

        issuer.issue(request, List.of(item));

        InOrder order = inOrder(submission, itemRepository, eventPublisher);
        order.verify(submission).createWalletRefund(any(), any(), any());
        order.verify(itemRepository).save(item);
        order.verify(eventPublisher).publishEvent(new WalletPawapayRefundInitiationEvent(item.getId(), refund.getId()));
        verify(submission, never()).initiate(any(), any());
    }

    @Test
    void issue_providerWithoutRefund_goesStraightToPayout() {
        WalletRefundRequestItemEntity item = item();
        stubDeposit();
        stubConfig(null);
        PawapayOperationEntity payout = operation(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        when(submission.createWalletPayout(eq(USER_ID), eq(deposit.getMsisdn()), eq(PROVIDER), eq("CI"), any(),
                eq("XOF"))).thenReturn(payout);

        issuer.issue(request, List.of(item));

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        verify(submission).createWalletPayout(eq(USER_ID), eq(deposit.getMsisdn()), eq(PROVIDER), eq("CI"),
                amount.capture(), eq("XOF"));
        assertThat(amount.getValue()).isEqualByComparingTo("9800");
        assertThat(item.getPawapayPayoutId()).isEqualTo(payout.getId());
        assertThat(item.getPawapayRefundId()).isNull();
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        verify(eventPublisher).publishEvent(new WalletPawapayRefundInitiationEvent(item.getId(), payout.getId()));
    }

    @Test
    void issue_refundClosedForProvider_goesStraightToPayout() {
        WalletRefundRequestItemEntity item = item();
        stubDeposit();
        stubConfig(CLOSED);
        when(submission.createWalletPayout(any(), any(), any(), any(), any(), any()))
                .thenReturn(operation(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND, BigDecimal.TEN));

        issuer.issue(request, List.of(item));

        verify(submission, never()).createWalletRefund(any(), any(), any());
        assertThat(item.getPawapayPayoutId()).isNotNull();
    }

    @Test
    void issue_providerAbsentFromConfiguration_goesStraightToPayout() {
        WalletRefundRequestItemEntity item = item();
        stubDeposit();
        when(client.activeConfiguration()).thenReturn(Map.of());
        when(submission.createWalletPayout(any(), any(), any(), any(), any(), any()))
                .thenReturn(operation(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND, BigDecimal.TEN));

        issuer.issue(request, List.of(item));

        verify(submission, never()).createWalletRefund(any(), any(), any());
        assertThat(item.getPawapayPayoutId()).isNotNull();
    }

    @Test
    void issue_itemAlreadyHasRefundId_isSkipped() {
        WalletRefundRequestItemEntity withRefund = item();
        withRefund.setPawapayRefundId(UUID.randomUUID());
        WalletRefundRequestItemEntity withPayout = item();
        withPayout.setPawapayPayoutId(UUID.randomUUID());

        issuer.issue(request, List.of(withRefund, withPayout));

        verifyNoInteractions(submission, operationRepository, client, itemRepository, eventPublisher);
    }

    @Test
    void issue_depositNotFound_leavesItemPendingForRecovery() {
        WalletRefundRequestItemEntity item = item();
        when(operationRepository.findById(deposit.getId())).thenReturn(Optional.empty());

        issuer.issue(request, List.of(item));

        assertPendingUnlinked(item);
    }

    @Test
    void issue_unreadablePaymentRef_leavesItemPending() {
        WalletRefundRequestItemEntity item = item();
        item.setPaymentIntentId("pi_stripe");

        issuer.issue(request, List.of(item));

        assertPendingUnlinked(item);
        verifyNoInteractions(operationRepository);
    }

    @Test
    void issue_configurationUnreadable_leavesItemPendingForRecovery() {
        WalletRefundRequestItemEntity item = item();
        stubDeposit();
        when(client.activeConfiguration()).thenThrow(new RestClientException("pawaPay indisponible"));

        issuer.issue(request, List.of(item));

        assertPendingUnlinked(item);
    }

    @Test
    void issue_reservationFails_leavesItemPendingForRecovery() {
        WalletRefundRequestItemEntity item = item();
        stubDeposit();
        stubConfig(OPEN);
        when(submission.createWalletRefund(any(), any(), any())).thenThrow(new IllegalStateException("base"));

        issuer.issue(request, List.of(item));

        assertPendingUnlinked(item);
    }

    private void assertPendingUnlinked(WalletRefundRequestItemEntity item) {
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PENDING);
        assertThat(item.getPawapayRefundId()).isNull();
        assertThat(item.getPawapayPayoutId()).isNull();
        verify(itemRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
        verify(submission, never()).initiate(any(), any());
    }

    // ── initiate ─────────────────────────────────────────────────────────────

    @Test
    void issue_pawapayUnavailable_keepsItemLinkedForPoller() {
        WalletRefundRequestItemEntity item = item();
        PawapayOperationEntity refund = operation(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        item.setPawapayRefundId(refund.getId());
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        when(operationRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(submission.initiate(refund, "wallet-refund-" + item.getId())).thenThrow(PawapayErrors.providerUnavailable());

        Optional<WalletPawapayRefundIssuer.Rejection> settled = issuer.initiate(item.getId(), refund.getId());

        assertThat(settled).isEmpty();
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        assertThat(item.getPawapayRefundId()).isEqualTo(refund.getId());
        verify(itemRepository, never()).save(any());
        verifyNoInteractions(adminAlertService);
    }

    @Test
    void initiate_accepted_leavesItemProcessing() {
        PawapayOperationEntity refund = operation(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        UUID itemId = UUID.randomUUID();
        when(operationRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(submission.initiate(refund, "wallet-refund-" + itemId)).thenReturn(PawapayInitiationResult.accepted());

        assertThat(issuer.initiate(itemId, refund.getId())).isEmpty();

        verifyNoInteractions(itemRepository);
    }

    @Test
    void initiate_operationAlreadySubmitted_isNotSentTwice() {
        PawapayOperationEntity refund = operation(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        refund.setStatus(PawapayOperationStatus.ACCEPTED);
        when(operationRepository.findById(refund.getId())).thenReturn(Optional.of(refund));

        assertThat(issuer.initiate(UUID.randomUUID(), refund.getId())).isEmpty();

        verify(submission, never()).initiate(any(), any());
    }

    @Test
    void initiate_operationMissing_doesNothing() {
        UUID opId = UUID.randomUUID();
        when(operationRepository.findById(opId)).thenReturn(Optional.empty());

        assertThat(issuer.initiate(UUID.randomUUID(), opId)).isEmpty();

        verifyNoInteractions(submission, itemRepository);
    }

    @Test
    void initiate_refundRejected_returnsRejectionWithoutTouchingItem() {
        PawapayOperationEntity refund = operation(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        when(operationRepository.findById(refund.getId())).thenReturn(Optional.of(refund));
        when(submission.initiate(any(), any())).thenReturn(new PawapayInitiationResult(
                PawapayInitiationResult.Outcome.REJECTED, "REFUND_NOT_ALLOWED", "non"));

        assertThat(issuer.initiate(UUID.randomUUID(), refund.getId())).contains(
                new WalletPawapayRefundIssuer.Rejection(PawapayOperationKind.REFUND, refund.getId(), "REFUND_NOT_ALLOWED"));

        verifyNoInteractions(itemRepository, adminAlertService, auditService);
    }

    @Test
    void initiate_payoutRejected_returnsRejection() {
        PawapayOperationEntity payout = operation(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        when(operationRepository.findById(payout.getId())).thenReturn(Optional.of(payout));
        when(submission.initiate(any(), any())).thenReturn(new PawapayInitiationResult(
                PawapayInitiationResult.Outcome.REJECTED, "INVALID_RECIPIENT", "non"));

        assertThat(issuer.initiate(UUID.randomUUID(), payout.getId())).contains(
                new WalletPawapayRefundIssuer.Rejection(PawapayOperationKind.PAYOUT, payout.getId(), "INVALID_RECIPIENT"));
    }

    @Test
    void initiate_operationOlderThanSendAge_isLeftCreatedForPoller() {
        // Tour 1, point 1 : envoyée trop tard, l'opération pourrait être acceptée APRÈS que le
        // poller l'a classée rejetée et lancé le versement de repli (double mouvement).
        WalletRefundRequestItemEntity item = item();
        PawapayOperationEntity refund = operation(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        ReflectionTestUtils.setField(refund, "createdAt", LocalDateTime.now(ZoneOffset.UTC).minusSeconds(61));
        item.setPawapayRefundId(refund.getId());
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        when(operationRepository.findById(refund.getId())).thenReturn(Optional.of(refund));

        assertThat(issuer.initiate(item.getId(), refund.getId())).isEmpty();

        verifyNoInteractions(submission, client, itemRepository);
        assertThat(refund.getStatus()).isEqualTo(PawapayOperationStatus.CREATED);
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        assertThat(item.getPawapayRefundId()).isEqualTo(refund.getId());
    }

    @Test
    void initiate_operationWithoutCreationDate_isNotSent() {
        PawapayOperationEntity refund = operation(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        ReflectionTestUtils.setField(refund, "createdAt", null);
        when(operationRepository.findById(refund.getId())).thenReturn(Optional.of(refund));

        assertThat(issuer.initiate(UUID.randomUUID(), refund.getId())).isEmpty();

        verifyNoInteractions(submission);
    }

    @Test
    void applyPawapayOutcome_refundFailed_fallsBackToPayoutForTheRequestUser() {
        WalletRefundRequestItemEntity item = item();
        item.setPawapayRefundId(UUID.randomUUID());
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        UUID requestUser = UUID.randomUUID();
        request.setUserId(requestUser);
        stubDeposit();
        PawapayOperationEntity payout = operation(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND,
                new BigDecimal("9800"));
        when(submission.createWalletPayout(eq(requestUser), eq(deposit.getMsisdn()), eq(PROVIDER), eq("CI"), any(),
                eq("XOF"))).thenReturn(payout);

        issuer.applyPawapayOutcome(request, item, PawapayOperationKind.REFUND, false, "REFUND_NOT_ALLOWED");

        assertThat(item.getPawapayPayoutId()).isEqualTo(payout.getId());
        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        verify(auditService).log(eq("wallet_refund_request"), eq(request.getId()), eq("REFUND_FALLBACK_PAYOUT"),
                eq(requestUser), anyMap());
        verify(eventPublisher).publishEvent(new WalletPawapayRefundInitiationEvent(item.getId(), payout.getId()));
    }

    @Test
    void applyPawapayOutcome_payoutFailed_marksFailedAndAlerts() {
        WalletRefundRequestItemEntity item = item();
        item.setPawapayPayoutId(UUID.randomUUID());
        item.setStatus(WalletRefundItemStatus.PROCESSING);

        issuer.applyPawapayOutcome(request, item, PawapayOperationKind.PAYOUT, false, "INVALID_RECIPIENT");

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
        assertThat(item.getFailureReason()).isEqualTo("INVALID_RECIPIENT");
        verify(adminAlertService).raise(eq("wallet-self-refund-failed"), anyString(), anyMap());
    }

    // ── applyPawapayOutcome ──────────────────────────────────────────────────

    @Test
    void applyPawapayOutcome_itemAlreadyTerminal_isIgnored() {
        WalletRefundRequestItemEntity item = item();
        item.setStatus(WalletRefundItemStatus.REFUNDED);

        issuer.applyPawapayOutcome(request, item, PawapayOperationKind.PAYOUT, false, "X");

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.REFUNDED);
        verifyNoInteractions(itemRepository, adminAlertService, submission);
    }

    @Test
    void applyPawapayOutcome_refundFailedButPayoutAlreadyLinked_doesNotRelaunchPayout() {
        WalletRefundRequestItemEntity item = item();
        item.setPawapayRefundId(UUID.randomUUID());
        item.setPawapayPayoutId(UUID.randomUUID());
        item.setStatus(WalletRefundItemStatus.PROCESSING);

        issuer.applyPawapayOutcome(request, item, PawapayOperationKind.REFUND, false, "X");

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.PROCESSING);
        verifyNoInteractions(submission, itemRepository, auditService, eventPublisher);
    }

    @Test
    void applyPawapayOutcome_fallbackReservationFails_marksFailedAndAlerts() {
        WalletRefundRequestItemEntity item = item();
        item.setPawapayRefundId(UUID.randomUUID());
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        stubDeposit();
        when(submission.createWalletPayout(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("base"));

        issuer.applyPawapayOutcome(request, item, PawapayOperationKind.REFUND, false, "X");

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
        assertThat(item.getFailureReason()).isEqualTo("pawapay-fallback-not-created");
        assertThat(item.getPawapayPayoutId()).isNull();
        verify(adminAlertService).raise(eq("wallet-self-refund-failed"), anyString(), anyMap());
        verifyNoInteractions(auditService, eventPublisher);
    }

    @Test
    void applyPawapayOutcome_refundFailedAndDepositMissing_marksFailed() {
        WalletRefundRequestItemEntity item = item();
        item.setPawapayRefundId(UUID.randomUUID());
        item.setStatus(WalletRefundItemStatus.PROCESSING);
        when(operationRepository.findById(deposit.getId())).thenReturn(Optional.empty());

        issuer.applyPawapayOutcome(request, item, PawapayOperationKind.REFUND, false, "X");

        assertThat(item.getStatus()).isEqualTo(WalletRefundItemStatus.FAILED);
        assertThat(item.getFailureReason()).isEqualTo("pawapay-deposit-missing");
    }

    @Test
    void applyPawapayOutcome_payoutFailedWithoutCode_usesDefaultReason() {
        WalletRefundRequestItemEntity item = item();
        item.setPawapayPayoutId(UUID.randomUUID());
        item.setStatus(WalletRefundItemStatus.PROCESSING);

        issuer.applyPawapayOutcome(request, item, PawapayOperationKind.PAYOUT, false, null);

        assertThat(item.getFailureReason()).isEqualTo("pawapay-payout-failed");
    }

    @Test
    void applyPawapayOutcome_longFailureCode_isTruncatedToColumnWidth() {
        WalletRefundRequestItemEntity item = item();
        item.setPawapayPayoutId(UUID.randomUUID());
        item.setStatus(WalletRefundItemStatus.PROCESSING);

        issuer.applyPawapayOutcome(request, item, PawapayOperationKind.PAYOUT, false, "X".repeat(64));

        assertThat(item.getFailureReason()).hasSize(60);
    }

    @Test
    void findItem_depositKind_isAlwaysEmpty() {
        assertThat(issuer.findItem(PawapayOperationKind.DEPOSIT, UUID.randomUUID())).isEmpty();
        verifyNoInteractions(itemRepository);
    }
}
