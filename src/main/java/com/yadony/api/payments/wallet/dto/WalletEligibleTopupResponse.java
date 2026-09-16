package com.yadony.api.payments.wallet.dto;

import com.yadony.api.payments.wallet.WalletSelfRefundService;
import com.yadony.api.payments.wallet.WalletTransactionEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletEligibleTopupResponse(
        UUID id,
        BigDecimal amount,
        BigDecimal originalAmount,
        String paymentRef,
        Instant createdAt
) {
    public static WalletEligibleTopupResponse from(WalletSelfRefundService.EligibleTopup eligible) {
        WalletTransactionEntity tx = eligible.topup();
        return new WalletEligibleTopupResponse(tx.getId(), eligible.remaining(), tx.getAmount(),
                tx.getPaymentRef(), tx.getCreatedAt());
    }
}
