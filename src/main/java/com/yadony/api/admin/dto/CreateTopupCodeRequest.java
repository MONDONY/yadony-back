package com.yadony.api.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Code facultatif (généré si absent), quantité 1 par défaut, expiration J+90 par défaut. */
public record CreateTopupCodeRequest(
        @Size(max = 64) String code,
        @NotNull BigDecimal amount,
        @NotNull String currency,
        Integer maxRedemptions,
        LocalDateTime expiresAt,
        @Size(max = 500) String note) {}
