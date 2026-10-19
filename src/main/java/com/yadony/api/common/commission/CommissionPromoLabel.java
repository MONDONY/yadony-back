package com.yadony.api.common.commission;

import com.yadony.api.common.i18n.Messages;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Libellé du code promo des devis sous grille (bids, demandes, fils) : source unique, pour que
 * les trois écrans annoncent la même remise.
 */
public final class CommissionPromoLabel {

    private CommissionPromoLabel() { }

    /**
     * Même libellé qu'avant les grilles (« Code X : n % de réduction », en points de commission),
     * mesuré sur l'écart réel de commission en montant rapporté au net : sous grille, le code ne
     * retranche pas des points fixes (minimum, tranches), seul l'écart en montant dit ce qu'il
     * fait gagner. Arrondi vers le bas.
     *
     * @param base     devis sans le code (mêmes autres réductions).
     * @param withCode devis avec le code.
     * @return {@code null} quand le code n'a pas d'effet ({@link CommissionResult#promoEffective()} faux).
     */
    public static String of(Messages messages, String code, CommissionResult base, CommissionResult withCode) {
        if (!withCode.promoEffective()) {
            return null;
        }
        long points = base.commission().subtract(withCode.commission())
                .multiply(BigDecimal.valueOf(100))
                .divide(withCode.net(), 0, RoundingMode.DOWN).longValue();
        return messages.get("quote.promo-label", code.toUpperCase(), points);
    }
}
