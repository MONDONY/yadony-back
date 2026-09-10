package com.yadony.api.admin.dto;

import com.yadony.api.payments.chargeback.ChargebackEntity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public record AdminChargebackResponse(
        UUID id,
        UUID bidId,
        long amountCents,
        /** Devise du litige Stripe (code ISO en majuscules) : un chargeback existe aussi en USD ou CAD. */
        String currency,
        String reason,
        String status,
        LocalDateTime openedAt
) {
    public static AdminChargebackResponse from(ChargebackEntity c) {
        return new AdminChargebackResponse(
                c.getId(),
                c.getBidId(),
                c.getAmount(),
                c.getCurrency() != null ? c.getCurrency().toUpperCase(java.util.Locale.ROOT) : null,
                c.getReason(),
                c.getStatus().name(),
                c.getOpenedAt() != null
                        ? LocalDateTime.ofInstant(c.getOpenedAt(), ZoneOffset.UTC)
                        : null
        );
    }
}
