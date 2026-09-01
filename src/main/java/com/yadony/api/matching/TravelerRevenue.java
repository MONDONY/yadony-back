package com.yadony.api.matching;

import com.yadony.api.payments.dto.CurrencyAmountRow;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Revenu voyageur = carte (escrow Stripe libéré) + espèces (net des bids CASH
 * livrés, qui ne créent aucun PaymentEntity), fusionnés PAR DEVISE.
 *
 * <p>Centralise la règle « carte + cash » pour que les vues de statistiques
 * (Activités, profil, analytics pro) ne divergent pas — et surtout qu'un futur
 * consommateur n'oublie ni le terme espèces, ni la ventilation par devise :
 * additionner 8 EUR et 5000 XOF dans un même total est le bug que cette classe
 * a successivement fermé sous ses deux formes.
 */
final class TravelerRevenue {

    private TravelerRevenue() {}

    /**
     * Fusionne carte + cash par code devise (majuscule). {@link TreeMap} : ordre
     * alphabétique stable, pour des DTO et des affichages déterministes.
     */
    static Map<String, BigDecimal> cardPlusCashByCurrency(List<CurrencyAmountRow> card,
                                                          List<CurrencyAmountRow> cash) {
        Map<String, BigDecimal> merged = new TreeMap<>();
        accumulate(merged, card);
        accumulate(merged, cash);
        return merged;
    }

    private static void accumulate(Map<String, BigDecimal> into, List<CurrencyAmountRow> rows) {
        if (rows == null) {
            return;
        }
        for (CurrencyAmountRow row : rows) {
            if (row == null || row.amount() == null) {
                continue;
            }
            into.merge(row.currency(), row.amount(), BigDecimal::add);
        }
    }
}
