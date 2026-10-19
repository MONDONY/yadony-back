package com.yadony.api.payments.supplement.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * De quoi composer une demande de supplément : bornes du net dans la devise du colis et montants
 * suggérés (articles de la grille du trajet, prix d'un kilo de plus).
 *
 * @param rail {@code STRIPE}, {@code PAWAPAY} ou {@code CASH}.
 */
public record SupplementOptionsResponse(String currency, BigDecimal minNet, BigDecimal maxNet,
                                        List<Suggestion> suggestions, String rail) {

    /**
     * @param kind    {@code GRID_ITEM} (article de la grille) ou {@code KG} (un kilo de plus).
     * @param label   libellé de l'article ; nul pour {@code KG}.
     * @param unitNet net d'une unité pour le voyageur.
     */
    public record Suggestion(String kind, String label, BigDecimal unitNet) {}
}
