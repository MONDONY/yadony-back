package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.util.UUID;

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
        /**
         * Taux de commission Yadony de la ligne « Commission » (override/global), hors code promo.
         * Sous grille : taux réel {@code commissionEur / netEur}, minimum compris, non borné.
         */
        BigDecimal rate,
        /** Commission Yadony hors code promo = netEur × rate (sous grille : commission de grille, minimum compris). */
        BigDecimal commissionEur,
        /** Total expéditeur, code promo déduit : exactement le montant que prélèvera le rail. */
        BigDecimal totalEur,
        /** true si un code promo a été appliqué. */
        boolean promoApplied,
        /** Ex. « Code WELCOME10 : −6 % » (null si pas de promo). */
        String promoLabel,
        /**
         * Devise de tous les montants ci-dessus, celle de l'annonce (code ISO en majuscules).
         * Le suffixe « Eur » des champs est historique : un devis sur un trajet en XOF est en XOF.
         */
        String currency,
        /** Sous grille : le minimum de la devise a relevé la commission finale. Toujours faux sans grille. */
        boolean minimumApplied,
        /** Sous grille : commission minimum de la devise ({@link #currency}) ; {@code null} sans grille. */
        BigDecimal minimumAmount,
        /** Grille de commission en vigueur, celle que le bid figera à sa création ; {@code null} sans grille. */
        UUID commissionGridId,
        /**
         * {@code "COMMISSION_MINIMUM"} quand un code promo valide est fourni mais ne change rien
         * (le minimum annule tout le gain) : il n'est alors ni appliqué ni consommé. {@code null} sinon.
         */
        String promoIneffectiveReason
) {}
