package com.yadony.api.payments.cash;

import java.math.BigDecimal;

/**
 * Répartition d'une commission espèces entre le portefeuille de la devise du colis (pris en
 * premier, sans conversion) et celui de la devise active du voyageur (complément converti).
 * Calculée par {@link WalletCommissionCollector#plan}, exécutée par ses méthodes {@code execute*}.
 *
 * <p>{@code covered} est vrai quand les deux portefeuilles couvrent la totalité : c'est la seule
 * situation où un débit a lieu (règle « tout ou rien »). {@code appliedRate} est nul quand rien
 * n'est converti (même devise, ou reste nul).
 */
public record CommissionSplit(String bidCurrency,
                              BigDecimal commission,
                              BigDecimal fromBidWallet,
                              BigDecimal remainingBid,
                              String activeCurrency,
                              BigDecimal remainingActive,
                              BigDecimal appliedRate,
                              BigDecimal bidWalletBalance,
                              BigDecimal activeBalance,
                              boolean covered) {

    public boolean sameCurrency() {
        return bidCurrency.equals(activeCurrency);
    }

    /** Commission totale exprimée dans la devise active (pour {@code requiredCommission}). */
    public BigDecimal commissionInActive() {
        if (sameCurrency() || appliedRate == null) {
            return sameCurrency() ? commission : remainingActive;
        }
        return commission.multiply(appliedRate).setScale(remainingActive.scale(), java.math.RoundingMode.HALF_UP);
    }
}
