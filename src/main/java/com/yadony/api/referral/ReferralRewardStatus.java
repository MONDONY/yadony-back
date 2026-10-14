package com.yadony.api.referral;

/**
 * État de la récompense du parrain sur une invitation ({@code referral_invitations.reward_status}).
 *
 * <ul>
 *   <li>{@link #NONE} : le filleul n'a pas encore eu de premier envoi livré ;</li>
 *   <li>{@link #AVAILABLE} : premier envoi livré, le parrain doit choisir sa récompense ;</li>
 *   <li>{@link #CHOSEN} : la récompense est attribuée ({@link ReferralRewardChoice} dit laquelle).</li>
 * </ul>
 */
public enum ReferralRewardStatus {
    NONE,
    AVAILABLE,
    CHOSEN
}
