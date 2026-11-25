package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Prix conseillés pour publier un trajet sur une route : médiane du prix au kilo et des
 * prix par article des trajets publiés récemment sur la même route, dans la même devise.
 *
 * @param currency      devise des montants (celle demandée par l'app)
 * @param pricePerKg    médiane du prix au kilo, arrondie à l'unité ; {@code null} quand
 *                      trop peu de trajets existent pour qu'elle ait un sens
 * @param sampleSize    nombre de trajets ayant servi au calcul du prix au kilo
 * @param items         prix par article les plus fréquents sur la route, du plus courant
 *                      au moins courant ; vide quand aucun n'a assez d'exemples
 */
public record PriceSuggestionResponse(
        String currency,
        BigDecimal pricePerKg,
        int sampleSize,
        List<ItemPriceSuggestion> items) {

    /**
     * @param label      libellé tel que les voyageurs l'écrivent le plus souvent
     * @param unitPrice  médiane du montant net par article, arrondie à l'unité
     * @param sampleSize nombre de trajets où cet article a un prix
     */
    public record ItemPriceSuggestion(String label, BigDecimal unitPrice, int sampleSize) {}
}
