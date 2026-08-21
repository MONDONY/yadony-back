package com.yadony.api.payments.wallet.dto;

import com.yadony.api.payments.wallet.WalletTransactionEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record WalletEligibleTopupResponse(
        UUID id,
        BigDecimal amount,
        String paymentRef,
        Instant createdAt
) {
    public static WalletEligibleTopupResponse from(WalletTransactionEntity tx) {
        return new WalletEligibleTopupResponse(tx.getId(), tx.getAmount(), tx.getPaymentRef(), tx.getCreatedAt());
    }
}
