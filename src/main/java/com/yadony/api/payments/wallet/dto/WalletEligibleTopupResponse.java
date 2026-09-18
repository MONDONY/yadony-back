package com.yadony.api.payments.wallet.dto;

import com.yadony.api.payments.wallet.WalletSelfRefundService;
import com.yadony.api.payments.wallet.WalletTransactionEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Recharge remboursable : {@code amount} brut restant, {@code feeAmount} retenu si elle est remboursée (additif). */
public record WalletEligibleTopupResponse(
        UUID id,
        BigDecimal amount,
        BigDecimal originalAmount,
        String paymentRef,
        Instant createdAt,
        BigDecimal feeAmount
) {
    public static WalletEligibleTopupResponse from(WalletSelfRefundService.EligibleTopup eligible) {
        WalletTransactionEntity tx = eligible.topup();
        return new WalletEligibleTopupResponse(tx.getId(), eligible.remaining(), tx.getAmount(),
                tx.getPaymentRef(), tx.getCreatedAt(), eligible.fee());
    }
}
