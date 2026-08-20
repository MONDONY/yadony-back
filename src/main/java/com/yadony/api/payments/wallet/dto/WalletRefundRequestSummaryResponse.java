package com.yadony.api.payments.wallet.dto;

import com.yadony.api.payments.wallet.WalletRefundRequestEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record WalletRefundRequestSummaryResponse(
        UUID id,
        String currency,
        BigDecimal amount,
        String channel,
        String status,
        LocalDateTime requestedAt,
        LocalDateTime resolvedAt
) {
    public static WalletRefundRequestSummaryResponse from(WalletRefundRequestEntity entity) {
        return new WalletRefundRequestSummaryResponse(
                entity.getId(), entity.getCurrency(), entity.getAmount(),
                entity.getChannel().name(), entity.getStatus().name(),
                entity.getRequestedAt(), entity.getResolvedAt());
    }
}
