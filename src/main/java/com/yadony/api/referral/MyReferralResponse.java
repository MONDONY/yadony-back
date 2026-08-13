package com.yadony.api.referral;

/**
 * Response DTO for GET /me/referral.
 */
public record MyReferralResponse(
        String code,
        String shareUrl,
        int totalInvited,
        int signedUp,
        int rewarded,
        int totalEarnedCents,
        boolean hasBeenReferred,
        /**
         * Devise du total ci-dessus. Les crédits sont versés dans la devise active
         * du parrain au moment du versement : sans ce champ, le client ne pourrait
         * pas légender le montant, et cumuler toutes devises confondues donnerait
         * un total dépourvu de sens.
         */
        String currency,
        /**
         * Montant unitaire de la récompense, en centimes, tel que configuré par
         * {@code yadony.referral.reward-amount-cents}. Exposé pour que le client
         * annonce le bon montant dans la bonne devise : les libellés d'invitation
         * portaient « 5 € » en dur, ce qui devenait faux dès que le parrain
         * travaillait dans une autre devise.
         */
        int rewardAmountCents
) {}
