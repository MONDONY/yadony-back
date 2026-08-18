package com.yadony.api.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Répartition d'un montant NÉGOCIÉ entre le net du voyageur et la commission Yadony.
 *
 * <p>La négociation porte sur le <b>brut</b> : le nombre que l'expéditeur tape est
 * exactement ce qu'il paiera. Le net du voyageur en est déduit, jamais l'inverse.
 *
 * <p>La commission est obtenue <b>par soustraction</b> et non par {@code net × taux} :
 * c'est ce qui garantit l'invariant {@code net + commission = brut} au centime près,
 * quel que soit l'arrondi. Un calcul direct des deux côtés peut faire dériver la somme
 * d'un centime, et l'expéditeur verrait alors un détail qui ne totalise pas ce qu'il paie.
 */
public final class BidNegotiationPricing {

    private BidNegotiationPricing() { }

    public record Split(BigDecimal grossEur, BigDecimal netEur, BigDecimal commissionEur) { }

    public static Split split(BigDecimal grossEur, BigDecimal rate) {
        if (grossEur == null || grossEur.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Le montant négocié doit être strictement positif");
        }
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Le taux de commission ne peut pas être négatif");
        }

        BigDecimal gross = grossEur.setScale(2, RoundingMode.HALF_UP);
        BigDecimal net = gross.divide(BigDecimal.ONE.add(rate), 2, RoundingMode.HALF_UP);
        BigDecimal commission = gross.subtract(net);

        return new Split(gross, net, commission);
    }
}
