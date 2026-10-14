package com.yadony.api.referral;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un filleul du parrain connecté (GET /me/referral/referees, POST .../choose).
 * Jamais de téléphone : seulement le prénom et l'initiale du nom.
 *
 * @param invitationId     identifiant à passer à {@code POST /me/referral/rewards/{id}/choose}.
 * @param displayName      « Awa D. », {@code null} si le filleul n'a pas de prénom.
 * @param signedUpAt       date d'utilisation du code (inscription du filleul).
 * @param status           {@code SIGNED_UP}, {@code FIRST_DELIVERY} ou {@code REWARDED}.
 * @param rewardStatus     {@code NONE}, {@code AVAILABLE} ou {@code CHOSEN}.
 * @param rewardChoice     {@code WALLET_CREDIT}, {@code DISCOUNT_CODE}, {@code COMMISSION_VOUCHER}
 *                         (historique) ou {@code null} tant que rien n'est choisi.
 * @param rewardAvailableAt date à laquelle la récompense est devenue disponible.
 * @param rewardChosenAt   date du choix.
 * @param walletAmount     montant crédité si {@code WALLET_CREDIT}, sinon {@code null}.
 * @param walletCurrency   devise du crédit si {@code WALLET_CREDIT}, sinon {@code null}.
 * @param discountCode     code personnel si {@code DISCOUNT_CODE}, sinon {@code null}.
 */
public record ReferralRefereeResponse(
        UUID invitationId,
        String displayName,
        LocalDateTime signedUpAt,
        String status,
        ReferralRewardStatus rewardStatus,
        ReferralRewardChoice rewardChoice,
        LocalDateTime rewardAvailableAt,
        LocalDateTime rewardChosenAt,
        BigDecimal walletAmount,
        String walletCurrency,
        DiscountCode discountCode
) {

    /**
     * @param code      code à saisir au paiement (ex. {@code MERCI-ABOU-7K2F}).
     * @param percent   remise en pourcentage du prix payé (5 = 5 %).
     * @param expiresAt fin de validité (UTC).
     * @param used      déjà utilisé sur un envoi (rendu s'il est annulé sans livraison).
     * @param expired   validité dépassée.
     */
    public record DiscountCode(String code, int percent, LocalDateTime expiresAt, boolean used, boolean expired) {}
}
