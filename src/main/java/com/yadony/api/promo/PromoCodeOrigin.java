package com.yadony.api.promo;

/** D'où vient un code promo (V390). */
public enum PromoCodeOrigin {
    /** Créé par un administrateur. */
    ADMIN,
    /** Code personnel offert au parrain en récompense (V321). */
    REFERRAL_REWARD,
    /** Code de bienvenue offert au filleul à l'inscription. */
    REFERRAL_WELCOME
}
