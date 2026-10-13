package com.yadony.api.flights;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Repli « dates proches » d'une recherche en jours précis.
 *
 * <p>{@code prices_for_dates} ne lit que le cache d'Aviasales : un couple exact (ex. Abidjan → Paris
 * le 23/12, retour le 02/01) que personne n'a cherché récemment n'y figure pas, alors que le même
 * trajet existe à un ou deux jours près. On relance alors la recherche sur les mois (voir
 * {@link FlightQuery#widenedToMonths()}) et ce filtre ne garde que les offres à {@link #WINDOW_DAYS}
 * jours au plus de chaque jour demandé, les plus proches d'abord, puis les moins chères.
 *
 * <p>Les dates comparées sont les dates locales telles qu'écrites dans {@code departure_at} et
 * {@code return_at} ({@code 2026-12-24T23:30:00+00:00} → le 24), pas leur conversion en UTC :
 * c'est le jour que l'utilisateur lira sur son billet.
 */
final class FlightNearbyDates {

    /** Écart maximal, en jours, de part et d'autre de chaque date demandée. */
    static final int WINDOW_DAYS = 3;

    private FlightNearbyDates() {
    }

    /**
     * @param requested la recherche d'origine, avec ses jours précis
     * @param offers    les offres de la recherche élargie aux mois
     * @param limit     nombre maximal d'offres rendues
     */
    static List<TravelpayoutsPrice> filter(FlightQuery requested, List<TravelpayoutsPrice> offers, int limit) {
        LocalDate out = day(requested.departure());
        LocalDate back = day(requested.returnDate());
        return offers.stream()
                .filter(Objects::nonNull)
                .filter(p -> p.price() != null)
                .filter(p -> within(out, p.departureAt()) && within(back, p.returnAt()))
                .sorted(Comparator.<TravelpayoutsPrice>comparingLong(p -> gap(out, p.departureAt()) + gap(back, p.returnAt()))
                        .thenComparing(TravelpayoutsPrice::price))
                .limit(limit)
                .toList();
    }

    /** Le jour demandé, ou {@code null} si la date était un mois entier ou absente (rien à filtrer). */
    private static LocalDate day(FlightDate date) {
        return date != null && date.isDay() ? date.day() : null;
    }

    private static boolean within(LocalDate wanted, String providerDateTime) {
        if (wanted == null) return true;
        LocalDate actual = localDate(providerDateTime);
        return actual != null && Math.abs(ChronoUnit.DAYS.between(wanted, actual)) <= WINDOW_DAYS;
    }

    private static long gap(LocalDate wanted, String providerDateTime) {
        if (wanted == null) return 0;
        LocalDate actual = localDate(providerDateTime);
        return actual == null ? Long.MAX_VALUE / 4 : Math.abs(ChronoUnit.DAYS.between(wanted, actual));
    }

    /** Les dix premiers caractères ({@code AAAA-MM-JJ}) : la date à l'heure du lieu, sans conversion. */
    static LocalDate localDate(String providerDateTime) {
        if (providerDateTime == null || providerDateTime.length() < 10) return null;
        try {
            return LocalDate.parse(providerDateTime.substring(0, 10));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
