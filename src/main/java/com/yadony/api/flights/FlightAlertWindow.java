package com.yadony.api.flights;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Période surveillée par une alerte prix : départ entre {@code departureFrom} et
 * {@code departureTo}, et, pour un aller-retour, retour entre {@code returnFrom} et
 * {@code returnTo}. Les bornes sont incluses ; deux bornes {@code null} = toutes les dates.
 *
 * <p>Travelpayouts ne filtre qu'au jour ou au mois : une période se ramène à un appel par
 * mois couvert (au plus deux, la période est limitée à 31 jours), et les offres hors période
 * sont écartées ici.
 */
record FlightAlertWindow(LocalDate departureFrom, LocalDate departureTo,
                         LocalDate returnFrom, LocalDate returnTo) {

    /** Durée maximale d'une période, en jours (bornes comprises) : au plus deux mois couverts. */
    static final int MAX_DAYS = 31;

    boolean roundTrip() {
        return returnFrom != null;
    }

    /** Appels au partenaire nécessaires, un par combinaison de mois (ou de jour précis). */
    List<FlightQuery> queries(String origin, String destination, boolean directOnly, String currency) {
        List<FlightDate> departures = dates(departureFrom, departureTo);
        List<FlightDate> returns = dates(returnFrom, returnTo);
        List<FlightQuery> queries = new ArrayList<>();
        for (FlightDate out : departures) {
            for (FlightDate back : returns) {
                queries.add(new FlightQuery(origin, destination, out, back, directOnly, currency));
            }
        }
        return queries;
    }

    /** Vrai si l'offre part (et revient) dans la période. */
    boolean accepts(TravelpayoutsPrice price) {
        if (departureFrom != null && !within(dayOf(price.departureAt()), departureFrom, departureTo)) {
            return false;
        }
        return returnFrom == null || within(dayOf(price.returnAt()), returnFrom, returnTo);
    }

    /** Clé de regroupement : deux alertes sur la même période partagent leurs appels. */
    String key() {
        return departureFrom + ".." + departureTo + "|" + returnFrom + ".." + returnTo;
    }

    private static List<FlightDate> dates(LocalDate from, LocalDate to) {
        if (from == null) {
            List<FlightDate> any = new ArrayList<>();
            any.add(null);
            return any;
        }
        if (from.equals(to)) {
            return List.of(FlightDate.ofDay(from));
        }
        List<FlightDate> months = new ArrayList<>();
        for (YearMonth m = YearMonth.from(from); !m.isAfter(YearMonth.from(to)); m = m.plusMonths(1)) {
            months.add(FlightDate.ofMonth(m));
        }
        return months;
    }

    /** « 2026-12-04T11:30:00+01:00 » → 2026-12-04, {@code null} si absent ou illisible. */
    static LocalDate dayOf(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.length() < 10) return null;
        try {
            return LocalDate.parse(isoDateTime.substring(0, 10));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean within(LocalDate day, LocalDate from, LocalDate to) {
        return day != null && !day.isBefore(from) && !day.isAfter(to);
    }
}
