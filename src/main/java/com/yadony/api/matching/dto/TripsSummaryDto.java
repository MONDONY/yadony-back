package com.yadony.api.matching.dto;

import com.fasterxml.jackson.annotation.JsonGetter;
import java.math.BigDecimal;

/**
 * Résumé d'activité d'un utilisateur sur une période.
 *
 * <p>Les anciens noms {@code kgSoldThisMonth} / {@code revenueThisMonth} restent
 * dans le JSON pour les clients déployés qui les lisent encore, mais ce sont des
 * alias de sérialisation, pas des champs : ils ne peuvent pas diverger des
 * valeurs réelles, et se suppriment en deux lignes quand ces clients sont
 * éteints.
 *
 * <p>{@code revenue} est exprimé dans {@code revenueCurrency}, la devise
 * d'affichage du voyageur. {@code revenueConverted} dit si au moins une devise
 * d'origine en diffère : le client préfixe alors la valeur d'un « ≈ », le
 * détail par devise vivant sur {@code /trips-summary/revenues}.
 */
public record TripsSummaryDto(
        long activeTrips,
        BigDecimal kgSold,
        BigDecimal revenue,
        long tripsPublished,
        long parcelsSent,
        String period,
        String revenueCurrency,
        boolean revenueConverted
) {
    public static TripsSummaryDto of(
            long activeTrips,
            BigDecimal kgSold,
            BigDecimal revenue,
            long tripsPublished,
            long parcelsSent,
            String period,
            String revenueCurrency,
            boolean revenueConverted) {
        return new TripsSummaryDto(
                activeTrips, kgSold, revenue, tripsPublished, parcelsSent, period,
                revenueCurrency, revenueConverted);
    }

    @JsonGetter("kgSoldThisMonth")
    public BigDecimal kgSoldThisMonth() {
        return kgSold;
    }

    @JsonGetter("revenueThisMonth")
    public BigDecimal revenueThisMonth() {
        return revenue;
    }
}
