package com.yadony.api.promo;

/** Sens de {@code promo_codes.rate}. */
public enum PromoDiscountKind {
    /** {@code rate} est retranché en points du taux de commission (codes historiques). */
    COMMISSION_POINTS,
    /**
     * {@code rate} est une remise en pourcentage du prix payé par l'expéditeur, financée sur
     * la commission Yadony et plafonnée à celle-ci : le net du voyageur n'est jamais touché.
     */
    PRICE_PERCENT
}
