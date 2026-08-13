package com.yadony.api.payments.cash;

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
    public boolean chargeNegotiationCashCommission(UUID travelerId, UUID senderId, UUID threadId, BigDecimal netAmount) {
        return cashCommissionService.chargeNegotiationCommission(travelerId, senderId, threadId, netAmount);
    }
}
