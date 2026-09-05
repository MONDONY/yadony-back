package com.yadony.api.payments.pawapay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    void networkFailure_leavesOperationCreated_andAnswers502() {
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
}
