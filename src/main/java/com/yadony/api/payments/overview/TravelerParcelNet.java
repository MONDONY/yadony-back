package com.yadony.api.payments.overview;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Net voyageur d'UN paiement, tel que « Mon argent » le montre : passe par
 * {@link MoneyStateResolver#resolve}, donc même formule (payé moins commission), même exclusion
 * d'un paiement remboursé (rien à montrer au voyageur) et même traitement d'un remboursement
 * partiel (le net reste payé moins commission, la ligne passe en « à l'étude »). Point d'entrée
 * public pour les autres packages de {@code payments/}, qui ne doivent pas recopier la formule.
 */
public final class TravelerParcelNet {

    private TravelerParcelNet() {
    }

    /** Net voyageur du paiement, zéro quand « Mon argent » n'aurait rien à afficher. */
    public static BigDecimal of(String paymentStatus, BigDecimal amount, BigDecimal commissionAmount,
                                BigDecimal refundedAmount) {
        MoneyRow row = new MoneyRow(MoneyRole.TRAVELER, null, null, null, null, null, null, null, null,
                null, null, null, null, null, java.util.UUID.randomUUID(), paymentStatus, null, amount,
                commissionAmount, refundedAmount, null, false, null, null, null, 0, 0, null, 0, 0);
        Optional<MoneyStateResolver.Resolution> resolved = MoneyStateResolver.resolve(row);
        return resolved.map(MoneyStateResolver.Resolution::amount).orElse(BigDecimal.ZERO);
    }
}
