package com.yadony.api.common.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Devis d'une commission par paliers, montants arrondis à l'unité mineure de la devise.
 *
 * @param net            net du voyageur.
 * @param commission     commission Yadony, arrondie une seule fois (HALF_UP).
 * @param gross          total payé par l'expéditeur, {@code net + commission}.
 * @param effectiveRate  {@code commission / net}, à 6 décimales.
 * @param minimumApplied vrai quand le minimum de la devise a relevé la commission.
 * @param promoEffective vrai seulement si un code promo est fourni ET que la commission finale
 *                       avec ce code est plus basse que sans lui (faux quand le minimum annule
 *                       tout le gain : le code ne doit alors pas être consommé).
 */
public record CommissionResult(BigDecimal net, BigDecimal commission, BigDecimal gross,
                               BigDecimal effectiveRate, boolean minimumApplied, boolean promoEffective) {

    /** Plus grand taux enregistrable : {@code bids_commission_rate_range} (V118) impose [0, 1[. */
    private static final BigDecimal MAX_STORABLE_RATE = new BigDecimal("0.999999");

    /** Précision des colonnes de taux (NUMERIC(7,6), V331) ; source unique pour les rails. */
    public static final int STORED_RATE_SCALE = 6;

    /** {@link #storableRate(BigDecimal)} appliqué au taux effectif de ce devis. */
    public BigDecimal storableRate() {
        return storableRate(effectiveRate);
    }

    /**
     * Taux effectif tel qu'on l'ENREGISTRE sur un objet à grille ({@code bids.commission_rate},
     * {@code negotiation_threads.commission_rate}, {@code promo_redemptions.applied_rate}) :
     * borné à 0,999999. Le minimum de la devise fait dépasser 100 % quand le net est sous ce
     * minimum (1,00 € sur 0,50 € = 200 %), ce que le CHECK refuserait. Pour ces objets la valeur
     * n'est qu'informative : les montants font foi, jamais {@code net × taux}.
     */
    public static BigDecimal storableRate(BigDecimal effectiveRate) {
        return effectiveRate.min(MAX_STORABLE_RATE).setScale(STORED_RATE_SCALE, RoundingMode.HALF_UP);
    }
}
