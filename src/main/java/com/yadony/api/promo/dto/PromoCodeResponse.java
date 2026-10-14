package com.yadony.api.promo.dto;

import com.yadony.api.promo.PromoCodeStatus;
import com.yadony.api.promo.PromoDiscountKind;
import com.yadony.api.promo.PromoCodeTarget;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PromoCodeResponse(
        UUID id,
        String code,
        BigDecimal rate,
        PromoCodeTarget target,
        LocalDateTime validFrom,
        LocalDateTime validTo,
        Integer maxRedemptions,
        int perUserLimit,
        int redeemedCount,
        PromoCodeStatus status,
        LocalDateTime createdAt,
        /** Propriétaire d'un code personnel de parrainage, {@code null} pour un code global. */
        UUID ownerUserId,
        /** Sens de {@code rate} : points de commission ou pourcentage du prix payé. */
        PromoDiscountKind discountKind
) {}
