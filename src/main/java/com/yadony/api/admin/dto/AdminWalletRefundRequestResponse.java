package com.yadony.api.admin.dto;

import com.yadony.api.payments.wallet.WalletRefundRequestEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Une ligne de la file des demandes de remboursement wallet (suppression de compte). */
public record AdminWalletRefundRequestResponse(
        UUID id,
        UUID userId,
        String currency,
        BigDecimal amount,
        String status,
        LocalDateTime requestedAt
) {
    public static AdminWalletRefundRequestResponse from(WalletRefundRequestEntity e) {
        return new AdminWalletRefundRequestResponse(
                e.getId(), e.getUserId(), e.getCurrency(), e.getAmount(),
                e.getStatus().name(), e.getRequestedAt());
    }
}
