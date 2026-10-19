package com.yadony.api.common.commission;

import java.math.BigDecimal;

/**
 * Tranche d'une grille de commission marginale : le taux {@code rate} s'applique à la part du
 * net comprise entre {@code fromEur} et la borne de la tranche suivante (sans borne pour la
 * dernière).
 *
 * @param fromEur borne basse, saisie en euros, convertie dans la devise du prix par
 *                {@link com.yadony.api.payments.currency.SupportedCurrency#unitsPerEur()} sans arrondi.
 * @param rate    taux marginal de la tranche (0,07 pour 7 %).
 */
public record CommissionTier(BigDecimal fromEur, BigDecimal rate) {
}
