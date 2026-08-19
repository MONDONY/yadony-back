package com.yadony.api.admin.dto;

import com.yadony.api.payments.wallet.WalletAccountEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un portefeuille, en lecture seule.
 *
 * <p>⚠️ Le solde est stocke en UNITES ({@code BigDecimal} scale 2) et expose en CENTIMES :
 * le back-office formate a partir de centiemes entiers, et un nombre flottant JSON perdrait
 * de la precision sur des montants. La conversion vit ici, une seule fois.
 */
public record AdminWalletResponse(
        UUID id,
        UUID userId,
        long balanceCents,
        String currency,
        LocalDateTime updatedAt) {

    public static AdminWalletResponse from(WalletAccountEntity entity) {
        return new AdminWalletResponse(
                entity.getId(),
                entity.getUserId(),
                toCents(entity.getBalance()),
                entity.getCurrency(),
                entity.getUpdatedAt());
    }

    static long toCents(BigDecimal amount) {
        return amount == null ? 0L : amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
