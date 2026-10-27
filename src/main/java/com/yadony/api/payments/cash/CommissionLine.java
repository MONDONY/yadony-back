package com.yadony.api.payments.cash;

import java.math.BigDecimal;

/**
 * Un prélèvement sur UN portefeuille pour régler une commission.
 *
 * <p>{@code debitAmount} est exprimé dans la devise du portefeuille ({@code walletCurrency}) ;
 * {@code coveredInBid} est la part de la commission qu'il règle, dans la devise du colis. Ligne de
 * la devise du colis : les deux montants sont égaux et {@code appliedRate} est nul. Ligne convertie :
 * {@code appliedRate} est le nombre d'unités de la devise du portefeuille pour UNE unité de la
 * devise du colis (convention du grand livre, {@code wallet_transactions.applied_rate}), recalculé
 * sur les montants arrondis : c'est le taux réellement appliqué, pas celui de la table.
 */
public record CommissionLine(String walletCurrency,
                             BigDecimal debitAmount,
                             BigDecimal coveredInBid,
                             BigDecimal appliedRate) {

    /** Vrai quand ce prélèvement a nécessité une conversion (sinon c'est le portefeuille du colis). */
    public boolean converted() {
        return appliedRate != null;
    }
}
