package com.yadony.api.referral;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for GET /me/referral.
 *
 * <p>Lot 3 (2026-08-19/20) : le parrainage ne verse plus d'argent — il octroie un
 * bon de réduction de commission. Les anciens champs {@code totalEarnedCents} /
 * {@code currency} / {@code rewardAmountCents} n'ont donc plus de sens (aucune devise
 * n'entre en jeu) et sont remplacés par l'état des bons.
 */
public record MyReferralResponse(
        String code,
        String shareUrl,
        int totalInvited,
        int signedUp,
        int rewarded,
        boolean hasBeenReferred,
        /** Nombre de bons octroyés et pas encore consommés ni expirés. */
        int activeVoucherCount,
        /**
         * Facteur multiplicatif appliqué par un bon (0.50 = moitié prix), tel que
         * configuré par {@code yadony.voucher.factor}. Toujours renseigné (barème
         * courant), même sans bon actif — c'est la promesse faite au prochain
         * parrainage, pas une propriété d'un bon en particulier.
         */
        BigDecimal voucherFactor,
        /** Expiration du bon actif le plus proche, {@code null} si aucun bon actif. */
        LocalDateTime nextVoucherExpiresAt
) {}
