package com.yadony.api.common.commission;

import com.yadony.api.promo.PromoDiscount;

import java.math.BigDecimal;

/**
 * Réductions personnelles appliquées à la commission de grille, dans l'ordre de la spec
 * (§4.2) : taux spécial, puis code promo, puis minimum, puis bon de parrainage.
 *
 * @param overrideRate  taux spécial ({@code commission_rate_override}), déjà réduit au plus
 *                      petit des deux overrides voyageur et expéditeur ; {@code null} sans taux spécial.
 * @param promo         remise du code promo validé ; {@code null} sans code.
 * @param voucherFactor facteur du bon de parrainage (0,5 par défaut), appliqué après le
 *                      minimum ; {@code null} sans bon.
 */
public record CommissionReductions(BigDecimal overrideRate, PromoDiscount promo, BigDecimal voucherFactor) {

    /** Aucune réduction : commission de grille, minimum compris. */
    public static final CommissionReductions NONE = new CommissionReductions(null, null, null);

    /**
     * Réductions figées sur un bid à l'ouverture de sa négociation (spec §4.3) : taux spécial et
     * facteur du bon, jamais de code promo. Relues pour convertir brut et net du fil, et par les
     * rails pour un net négocié seul.
     */
    public static CommissionReductions frozen(BigDecimal overrideRate, BigDecimal voucherFactor) {
        return new CommissionReductions(overrideRate, null, voucherFactor);
    }

    /** Mêmes réductions sans le code promo, pour mesurer ce que le code fait gagner. */
    public CommissionReductions withoutPromo() {
        return frozen(overrideRate, voucherFactor);
    }
}
