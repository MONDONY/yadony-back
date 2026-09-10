package com.yadony.api.matching.dto;

import java.math.BigDecimal;

/**
 * Résultat du devis — permet à l'app d'afficher le total exact (promo inclus)
 * sans calculer localement.
 */
public record BidQuoteResponse(
        /** Montant net voyageur total (= gridNetEur + kgNetEur). */
        BigDecimal netEur,
        /** Part nette issue des articles de la grille (= Σ unitPriceNet × quantité). 0 si mode KG. */
        BigDecimal gridNetEur,
        /** Part nette issue du poids (= weightKg × pricePerKg). 0 si mode GRID. */
        BigDecimal kgNetEur,
        /** Taux de commission Yadony effectif (promo/override/global). */
        BigDecimal rate,
        /** Commission Yadony = netEur × rate. */
        BigDecimal commissionEur,
        /** Total expéditeur = netEur + commissionEur. */
        BigDecimal totalEur,
        /** true si un code promo a été appliqué. */
        boolean promoApplied,
        /** Ex. « Code WELCOME10 : −6 % » (null si pas de promo). */
        String promoLabel,
        /**
         * Devise de tous les montants ci-dessus, celle de l'annonce (code ISO en majuscules).
         * Le suffixe « Eur » des champs est historique : un devis sur un trajet en XOF est en XOF.
         */
        String currency
) {}
