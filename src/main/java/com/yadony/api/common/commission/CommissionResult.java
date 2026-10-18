package com.yadony.api.common.commission;

import java.math.BigDecimal;

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
}
