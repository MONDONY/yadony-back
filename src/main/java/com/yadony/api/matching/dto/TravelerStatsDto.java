package com.yadony.api.matching.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code monthlyRevenue}/{@code totalRevenue} sont exprimés dans {@code currency}
 * (devise ACTIVE du voyageur) : les revenus encaissés dans d'autres devises y sont
 * convertis au taux courant — c'est une estimation d'affichage, pas un solde. La
 * réalité comptable est dans les ventilations {@code *RevenueByCurrency}, une
 * entrée par devise réellement encaissée, montants jamais convertis.
 */
public record TravelerStatsDto(
        BigDecimal monthlyRevenue,
        BigDecimal totalRevenue,
        long monthlyTrips,
        long monthlyParcelsDelivered,
        double acceptanceRate,
        BigDecimal averageRating,
        List<DestinationStat> topDestinations,
        // ── Vue d'ensemble tout-temps (cockpit) ──
        long totalTripsCompleted,
        long activeTrips,
        long totalParcelsDelivered,
        long parcelsInTransit,
        int ratingCount,
        // ── Multidevise ──
        String currency,
        List<CurrencyRevenue> monthlyRevenueByCurrency,
        List<CurrencyRevenue> totalRevenueByCurrency
) {
    public record DestinationStat(String from, String to, long count) {}

    /** Montant encaissé dans une devise, tel quel — jamais converti. */
    public record CurrencyRevenue(String currency, BigDecimal amount) {}
}
