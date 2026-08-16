package com.yadony.api.payments.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.AcceptanceStatusDto;
import com.yadony.api.payments.cash.dto.ConfirmAcceptanceResponse;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.requests.CashGatePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class CashGateAdapterTest {

    @Mock private WalletService walletService;
    @Mock private CashCommissionService cashCommissionService;

    private CashGatePort adapter;

    @BeforeEach
    void setUp() {
        adapter = new CashGateAdapter(walletService, cashCommissionService);
    }

    @Test
    void settleNegotiationCommission_delegatesToCommissionService() {
        UUID traveler = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID thread = UUID.randomUUID();
        when(cashCommissionService.settleNegotiationCommission(
                traveler, sender, thread, new BigDecimal("100.00"), CommissionSource.WALLET_FIRST))
            .thenReturn(AcceptBidResponse.accepted());

        AcceptBidResponse resp = adapter.settleNegotiationCommission(
                traveler, sender, thread, new BigDecimal("100.00"), CommissionSource.WALLET_FIRST);

        assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.ACCEPTED);
        verify(cashCommissionService).settleNegotiationCommission(
                traveler, sender, thread, new BigDecimal("100.00"), CommissionSource.WALLET_FIRST);
    }

    @Test
    void settleNegotiationCommission_propagatesFailureStatusWithoutThrowing() {
        UUID traveler = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        UUID thread = UUID.randomUUID();
        when(cashCommissionService.settleNegotiationCommission(
                traveler, sender, thread, new BigDecimal("50.00"), CommissionSource.CARD))
            .thenReturn(AcceptBidResponse.failed("card-declined"));

        AcceptBidResponse resp = adapter.settleNegotiationCommission(
                traveler, sender, thread, new BigDecimal("50.00"), CommissionSource.CARD);

        assertThat(resp.status()).isEqualTo(AcceptanceStatusDto.FAILED);
        assertThat(resp.error()).isEqualTo("card-declined");
    }

    @Test
    void hasSufficientFunds_delegatesToWalletService() {
        UUID traveler = UUID.randomUUID();
        when(walletService.getBalance(traveler, "EUR")).thenReturn(new BigDecimal("30.00"));

        assertThat(adapter.hasSufficientFunds(traveler, new BigDecimal("20.00"), "EUR")).isTrue();
        assertThat(adapter.hasSufficientFunds(traveler, new BigDecimal("40.00"), "EUR")).isFalse();
    }

    @Test
    void hasCommissionCard_delegatesToCommissionService() {
        UUID traveler = UUID.randomUUID();
        when(cashCommissionService.hasCommissionCard(traveler)).thenReturn(true);

        assertThat(adapter.hasCommissionCard(traveler)).isTrue();
        verify(cashCommissionService).hasCommissionCard(traveler);
    }

    @Test
    void confirmNegotiationCommission_delegatesToCommissionServiceWithSwappedArgOrder() {
        UUID traveler = UUID.randomUUID();
        UUID thread = UUID.randomUUID();
        when(cashCommissionService.confirmNegotiationCommissionAcceptance(thread, traveler))
            .thenReturn(ConfirmAcceptanceResponse.ok());

        ConfirmAcceptanceResponse resp = adapter.confirmNegotiationCommission(traveler, thread);

        assertThat(resp.accepted()).isTrue();
        verify(cashCommissionService).confirmNegotiationCommissionAcceptance(thread, traveler);
    }

    @Test
    void refundNegotiationCommissionIfCharged_delegatesToCommissionServiceWithSwappedArgOrder() {
        UUID traveler = UUID.randomUUID();
        UUID thread = UUID.randomUUID();
        when(cashCommissionService.refundNegotiationCommissionIfCharged(thread, traveler))
            .thenReturn(true);

        boolean refunded = adapter.refundNegotiationCommissionIfCharged(traveler, thread);

        assertThat(refunded).isTrue();
        verify(cashCommissionService).refundNegotiationCommissionIfCharged(thread, traveler);
    }
}
