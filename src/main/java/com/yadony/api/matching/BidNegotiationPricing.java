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

    /**
     * Brut à demander à l'expéditeur pour que le voyageur touche AU MOINS {@code netEur}
     * (FLUTTER-NS, contre-offre saisie en net) : le plus petit montant, à l'unité mineure de
     * la devise ({@code minorUnit} décimales : 2 pour l'euro, 0 pour le franc CFA), dont
     * {@link #split} rend un net supérieur ou égal à {@code netEur}.
     *
     * <p>Calcul : {@code brut = arrondi_supérieur(net × (1 + taux), minorUnit)}, puis une unité
     * mineure de moins tant que le net reste atteint (au plus un pas, l'arrondi au centime de
     * {@link #split} pouvant déjà l'atteindre). En euros, le net retrouvé par {@link #split}
     * est exactement {@code netEur} ; en franc CFA, il peut le dépasser de moins d'une unité.
     */
    public static BigDecimal grossForNet(BigDecimal netEur, BigDecimal rate, int minorUnit) {
        if (netEur == null || netEur.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Le net demandé doit être strictement positif");
        }
        if (rate == null || rate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Le taux de commission ne peut pas être négatif");
        }
        BigDecimal target = netEur.setScale(2, RoundingMode.HALF_UP);
        BigDecimal step = BigDecimal.ONE.movePointLeft(minorUnit);
        BigDecimal gross = target.multiply(BigDecimal.ONE.add(rate)).setScale(minorUnit, RoundingMode.CEILING);
        while (gross.subtract(step).signum() > 0
                && split(gross.subtract(step), rate).netEur().compareTo(target) >= 0) {
            gross = gross.subtract(step);
        }
        return gross;
    }
}
