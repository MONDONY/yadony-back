package com.yadony.api.referral;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO for GET /me/referral.
 *
 * <p>Compteurs (V321) : {@code totalInvited} compte toutes les invitations,
 * {@code signedUp} celles dont le filleul a un compte (cumulatif : un filleul qui a
 * déjà envoyé reste inscrit), {@code firstDelivery} celles dont le filleul a eu un
 * premier envoi livré, {@code rewarded} celles dont le parrain a reçu sa récompense et
 * {@code pendingRewardCount} celles dont la récompense attend son choix
 * ({@code firstDelivery = rewarded + pendingRewardCount}).
 *
 * <p>Les champs de bon ({@code activeVoucherCount}, {@code voucherFactor},
 * {@code nextVoucherExpiresAt}) sont gardés pour les versions de l'app qui les lisent :
 * le parrainage n'octroie plus de bon, mais ceux déjà octroyés restent consommables.
 */
public record MyReferralResponse(
        String code,
        String shareUrl,
        int totalInvited,
        int signedUp,
        int rewarded,
        boolean hasBeenReferred,
        /** Nombre de bons octroyés (avant V321) et pas encore consommés ni expirés. */
        int activeVoucherCount,
        /** Facteur des bons de commission ({@code yadony.voucher.factor}). */
        BigDecimal voucherFactor,
        /** Expiration du bon actif le plus proche, {@code null} si aucun bon actif. */
        LocalDateTime nextVoucherExpiresAt,
        /** Filleuls dont le premier envoi a été livré (récompense choisie ou à choisir). */
        int firstDelivery,
        /** Récompenses disponibles, en attente du choix du parrain. */
        int pendingRewardCount,
        /** Montant de la récompense « solde » ({@code yadony.referral.reward-wallet-amount}). */
        BigDecimal rewardWalletAmount,
        /** Devise du crédit de solde (EUR). */
        String rewardWalletCurrency,
        /** Remise du code personnel, en pourcentage du prix payé (5 = 5 %). */
        int rewardDiscountPercent
) {}
