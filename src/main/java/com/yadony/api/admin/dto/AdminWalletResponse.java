package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un portefeuille, en lecture seule.
 *
 * <p>⚠️ Le solde est stocke en UNITES ({@code BigDecimal} scale 2) et expose en CENTIMES :
 * le back-office formate a partir de centiemes entiers, et un nombre flottant JSON perdrait
 * de la precision sur des montants. La conversion vit ici, une seule fois.
 *
 * <p>{@code userName} (« Prénom Nom (@pseudo) ») est absent du JSON pour un compte supprimé : le portefeuille
 * reste listé, le nom non.
 */
public record AdminWalletResponse(
        UUID id,
        UUID userId,
        long balanceCents,
        String currency,
        LocalDateTime updatedAt,
        String userName) {

    /**
     * Centiemes de l'unite principale — <b>pas</b> l'unite mineure de la devise.
     *
     * <p>⚠️ Ne pas « corriger » en {@code CurrencyAmount.toMinor} : ce serait une regression
     * d'affichage. Le back-office divise systematiquement par 100, quelle que soit la devise.
     * Un paiement de 5 000 XOF sort donc a 500000 et s'affiche « 5 000,00 F CFA », ce qui est
     * juste ; avec {@code toMinor} (XOF a {@code minorUnit = 0}) il sortirait a 5000 et
     * s'afficherait « 50,00 F CFA ».
     *
     * <p>En revanche, tout code qui parlerait a un prestataire de paiement doit utiliser
     * {@code CurrencyAmount.toMinor}, seule conversion canonique : Stripe attend de vraies
     * unites mineures, et l'ecart serait d'un facteur 100 sur XOF et XAF.
     */
    public static long toCents(BigDecimal amount) {
        return amount == null ? 0L : amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }
}
