package com.yadony.api.referral;

/**
 * Récompense retenue par le parrain ({@code referral_invitations.reward_choice}).
 *
 * <p>{@link #COMMISSION_VOUCHER} ne se choisit pas : il marque les invitations
 * récompensées avant V321 par un bon -50 % sur la commission, octroyé d'office.
 */
public enum ReferralRewardChoice {
    /** Montant crédité sur le solde Yadony du parrain (EUR). */
    WALLET_CREDIT,
    /** Code promo personnel : remise en pourcentage du prix payé sur un prochain envoi. */
    DISCOUNT_CODE,
    /** Historique (avant V321) : bon de réduction de commission. */
    COMMISSION_VOUCHER;

    /** Les seules récompenses que l'API propose au parrain. */
    public boolean isSelectable() {
        return this != COMMISSION_VOUCHER;
    }
}
