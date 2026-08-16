package com.yadony.api.payments.cash;

import com.yadony.api.payments.cash.dto.AcceptBidResponse;
import com.yadony.api.payments.cash.dto.ConfirmAcceptanceResponse;
import com.yadony.api.payments.wallet.WalletService;
import com.yadony.api.requests.CashGatePort;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
public class CashGateAdapter implements CashGatePort {

    private final WalletService walletService;
    private final CashCommissionService cashCommissionService;

    public CashGateAdapter(WalletService walletService,
                           CashCommissionService cashCommissionService) {
        this.walletService = walletService;
        this.cashCommissionService = cashCommissionService;
    }

    @Override
    public boolean hasSufficientFunds(UUID travelerId, BigDecimal commissionAmount, String currency) {
        BigDecimal balance = walletService.getBalance(travelerId, currency);
        return balance.compareTo(commissionAmount) >= 0;
    }

    @Override
    public boolean hasCommissionCard(UUID travelerId) {
        return cashCommissionService.hasCommissionCard(travelerId);
    }

    @Override
    public AcceptBidResponse settleNegotiationCommission(
            UUID travelerId, UUID senderId, UUID threadId, BigDecimal netAmount, CommissionSource source) {
        return cashCommissionService.settleNegotiationCommission(travelerId, senderId, threadId, netAmount, source);
    }

    @Override
    public ConfirmAcceptanceResponse confirmNegotiationCommission(UUID travelerId, UUID threadId) {
        return cashCommissionService.confirmNegotiationCommissionAcceptance(threadId, travelerId);
    }

    @Override
    public boolean refundNegotiationCommissionIfCharged(UUID travelerId, UUID threadId) {
        return cashCommissionService.refundNegotiationCommissionIfCharged(threadId, travelerId);
    }
}
