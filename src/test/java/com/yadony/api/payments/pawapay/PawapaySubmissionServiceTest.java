package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.pawapay.dto.PawapayDepositRequest;
import com.yadony.api.payments.pawapay.dto.PawapayInitiationResult;
import com.yadony.api.payments.pawapay.dto.PawapayPayoutRequest;
import com.yadony.api.payments.pawapay.dto.PawapayRefundRequest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class PawapaySubmissionServiceTest {

    @Mock PawapayOperationService operations;
    @Mock PawapayClient client;
    @InjectMocks PawapaySubmissionService service;

    private PawapayOperationEntity created(PawapayOperationKind kind, UUID paymentId) {
        return new PawapayOperationEntity(UUID.randomUUID(), kind, paymentId, null,
                new BigDecimal("15000"), "XOF", "ORANGE_SEN", "SN", "221771234567");
    }

    @Test
    void submitDeposit_createsThenCallsThenMarks_withSameId() {
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity op = created(PawapayOperationKind.DEPOSIT, paymentId);
        when(operations.create(eq(PawapayOperationKind.DEPOSIT), eq(paymentId), any(), any(), any(), any(), any(), any())).thenReturn(op);
        when(client.initiateDeposit(any())).thenReturn(PawapayInitiationResult.accepted());
        when(operations.get(op.getId())).thenReturn(op);

        service.submitDeposit(paymentId, "221771234567", "ORANGE_SEN", "SN", new BigDecimal("15000"), "XOF",
                "bid-1", "https://ok", "https://ko");

        ArgumentCaptor<PawapayDepositRequest> req = ArgumentCaptor.forClass(PawapayDepositRequest.class);
        verify(client).initiateDeposit(req.capture());
        assertThat(req.getValue().depositId()).isEqualTo(op.getId());
        assertThat(req.getValue().customerMessage()).isEqualTo("yadony envoi");
        assertThat(req.getValue().successfulUrl()).isEqualTo("https://ok");
        verify(operations).markSubmitted(eq(op.getId()), any());
    }

    @Test
    void submitPayout_usesPayoutMessage() {
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity op = created(PawapayOperationKind.PAYOUT, paymentId);
        when(operations.create(eq(PawapayOperationKind.PAYOUT), eq(paymentId), any(), any(), any(), any(), any(), any())).thenReturn(op);
        when(client.initiatePayout(any())).thenReturn(PawapayInitiationResult.accepted());
        when(operations.get(op.getId())).thenReturn(op);

        service.submitPayout(paymentId, "221771234567", "ORANGE_SEN", "SN", new BigDecimal("13200"), "XOF", "bid-1");

        ArgumentCaptor<PawapayPayoutRequest> req = ArgumentCaptor.forClass(PawapayPayoutRequest.class);
        verify(client).initiatePayout(req.capture());
        assertThat(req.getValue().payoutId()).isEqualTo(op.getId());
        assertThat(req.getValue().customerMessage()).isEqualTo("yadony versement");
    }

    @Test
    void submitRefund_linksTheOriginalDeposit() {
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity deposit = created(PawapayOperationKind.DEPOSIT, paymentId);
        PawapayOperationEntity refund = created(PawapayOperationKind.REFUND, paymentId);
        when(operations.create(eq(PawapayOperationKind.REFUND), eq(paymentId), eq(deposit.getId()), any(), any(), any(), any(), any())).thenReturn(refund);
        when(client.initiateRefund(any())).thenReturn(PawapayInitiationResult.accepted());
        when(operations.get(refund.getId())).thenReturn(refund);

        service.submitRefund(paymentId, deposit, new BigDecimal("15000"));

        ArgumentCaptor<PawapayRefundRequest> req = ArgumentCaptor.forClass(PawapayRefundRequest.class);
        verify(client).initiateRefund(req.capture());
        assertThat(req.getValue().depositId()).isEqualTo(deposit.getId());
        assertThat(req.getValue().refundId()).isEqualTo(refund.getId());
    }

    @Test
    void submitWalletDeposit_createsWalletTopupOperationWithoutPayment() {
        UUID userId = UUID.randomUUID();
        PawapayOperationEntity op = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.WALLET_TOPUP, userId, null, null, new BigDecimal("10000"), "XOF",
                "ORANGE_CIV", "CI", "+2250734567890");
        when(operations.create(eq(PawapayOperationKind.DEPOSIT), eq(PawapayOperationPurpose.WALLET_TOPUP), eq(userId),
                isNull(), isNull(), any(), eq("XOF"), eq("ORANGE_CIV"), eq("CI"), eq("+2250734567890"))).thenReturn(op);
        when(client.initiateDeposit(any())).thenReturn(PawapayInitiationResult.accepted());
        when(operations.get(op.getId())).thenReturn(op);

        PawapayOperationEntity result = service.submitWalletDeposit(userId, "+2250734567890", "ORANGE_CIV", "CI",
                new BigDecimal("10000"), "XOF", "wallet-topup", "https://x/ok", "https://x/ko");

        assertThat(result.getId()).isEqualTo(op.getId());
        ArgumentCaptor<PawapayDepositRequest> captor = ArgumentCaptor.forClass(PawapayDepositRequest.class);
        verify(client).initiateDeposit(captor.capture());
        assertThat(captor.getValue().depositId()).isEqualTo(op.getId());
        verify(operations).markSubmitted(eq(op.getId()), any());
    }

    @Test
    void networkFailure_translatesTo502_andNeverMarksSubmitted() {
        // Revue ronde 1, point 3 : PawapayOperationService est mocké ici, donc rien ne
        // "reste CREATED" au sens base de données dans ce test — seuls le code d'erreur
        // et l'absence d'appel à markSubmitted sont vérifiables à ce niveau. La
        // persistance réelle de create() malgré un rollback appelant (le REQUIRES_NEW
        // qui rend cette phrase vraie en production) est prouvée séparément par
        // PawapayOperationServiceConcurrencyIT#create_commitsInItsOwnTransaction_evenWhenCallerRollsBack.
        UUID paymentId = UUID.randomUUID();
        PawapayOperationEntity op = created(PawapayOperationKind.DEPOSIT, paymentId);
        when(operations.create(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(op);
        when(client.initiateDeposit(any())).thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.submitDeposit(paymentId, "221771234567", "ORANGE_SEN", "SN",
                new BigDecimal("15000"), "XOF", "bid-1", null, null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("mobile-money-provider-unavailable");
        verify(operations, never()).markSubmitted(any(), any());
    }

    private PawapayOperationEntity walletOperation(PawapayOperationKind kind, UUID relatedOperationId) {
        return new PawapayOperationEntity(UUID.randomUUID(), kind, PawapayOperationPurpose.WALLET_REFUND,
                UUID.randomUUID(), null, relatedOperationId, new BigDecimal("9800"), "XOF", "ORANGE_CIV", "CI",
                "+2250734567890");
    }

    @Test
    void createWalletRefund_reservesRefundOfTheDepositWithoutCallingPawapay() {
        UUID userId = UUID.randomUUID();
        PawapayOperationEntity deposit = new PawapayOperationEntity(UUID.randomUUID(), PawapayOperationKind.DEPOSIT,
                PawapayOperationPurpose.WALLET_TOPUP, userId, null, null, new BigDecimal("10000"), "XOF",
                "ORANGE_CIV", "CI", "+2250734567890");
        PawapayOperationEntity refund = walletOperation(PawapayOperationKind.REFUND, deposit.getId());
        when(operations.create(PawapayOperationKind.REFUND, PawapayOperationPurpose.WALLET_REFUND, userId, null,
                deposit.getId(), new BigDecimal("9800"), "XOF", "ORANGE_CIV", "CI", deposit.getMsisdn()))
                .thenReturn(refund);

        assertThat(service.createWalletRefund(userId, deposit, new BigDecimal("9800"))).isSameAs(refund);

        verifyNoInteractions(client);
        verify(operations, never()).markSubmitted(any(), any());
    }

    @Test
    void createWalletPayout_reservesPayoutWithoutCallingPawapay() {
        UUID userId = UUID.randomUUID();
        PawapayOperationEntity payout = walletOperation(PawapayOperationKind.PAYOUT, null);
        when(operations.create(PawapayOperationKind.PAYOUT, PawapayOperationPurpose.WALLET_REFUND, userId, null,
                null, new BigDecimal("9800"), "XOF", "ORANGE_CIV", "CI", "2250734567890")).thenReturn(payout);

        assertThat(service.createWalletPayout(userId, "2250734567890", "ORANGE_CIV", "CI", new BigDecimal("9800"),
                "XOF")).isSameAs(payout);

        verifyNoInteractions(client);
    }

    @Test
    void initiate_refund_sendsReservedIdAndDeposit_andReturnsTheResponse() {
        UUID depositId = UUID.randomUUID();
        PawapayOperationEntity refund = walletOperation(PawapayOperationKind.REFUND, depositId);
        PawapayInitiationResult rejected = new PawapayInitiationResult(PawapayInitiationResult.Outcome.REJECTED,
                "REFUND_NOT_ALLOWED", "non");
        when(client.initiateRefund(any())).thenReturn(rejected);

        assertThat(service.initiate(refund, "wallet-refund-1")).isSameAs(rejected);

        ArgumentCaptor<PawapayRefundRequest> req = ArgumentCaptor.forClass(PawapayRefundRequest.class);
        verify(client).initiateRefund(req.capture());
        assertThat(req.getValue().refundId()).isEqualTo(refund.getId());
        assertThat(req.getValue().depositId()).isEqualTo(depositId);
        assertThat(req.getValue().amount()).isEqualByComparingTo("9800");
        assertThat(req.getValue().currency()).isEqualTo("XOF");
        verify(operations).markSubmitted(refund.getId(), rejected);
        verify(operations, never()).get(any());
    }

    @Test
    void initiate_payout_sendsRecipientAndClientReference() {
        PawapayOperationEntity payout = walletOperation(PawapayOperationKind.PAYOUT, null);
        when(client.initiatePayout(any())).thenReturn(PawapayInitiationResult.accepted());

        service.initiate(payout, "wallet-refund-1");

        ArgumentCaptor<PawapayPayoutRequest> req = ArgumentCaptor.forClass(PawapayPayoutRequest.class);
        verify(client).initiatePayout(req.capture());
        assertThat(req.getValue().payoutId()).isEqualTo(payout.getId());
        assertThat(req.getValue().phoneNumber()).isEqualTo(payout.getMsisdn());
        assertThat(req.getValue().provider()).isEqualTo("ORANGE_CIV");
        assertThat(req.getValue().clientReferenceId()).isEqualTo("wallet-refund-1");
        verify(operations).markSubmitted(eq(payout.getId()), any());
    }

    @Test
    void initiate_networkFailure_leavesOperationCreated_andThrows502() {
        PawapayOperationEntity refund = walletOperation(PawapayOperationKind.REFUND, UUID.randomUUID());
        when(client.initiateRefund(any())).thenThrow(new ResourceAccessException("timeout"));

        assertThatThrownBy(() -> service.initiate(refund, "wallet-refund-1"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("mobile-money-provider-unavailable");
        verify(operations, never()).markSubmitted(any(), any());
    }

    @Test
    void initiate_deposit_isRefused() {
        PawapayOperationEntity deposit = walletOperation(PawapayOperationKind.DEPOSIT, null);

        assertThatThrownBy(() -> service.initiate(deposit, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }
}
