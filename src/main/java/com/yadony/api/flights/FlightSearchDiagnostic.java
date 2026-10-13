package com.yadony.api.flights;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Ligne de diagnostic d'une recherche aller-retour à jours précis qui rend peu d'offres. Temporaire :
 * elle sert à comprendre, dans Sentry Logs, si le partenaire n'a vraiment rien ou si la page lue est
 * tronquée. Aucune donnée personnelle : trajet, dates et compteurs seulement.
 *
 * <p>Format (une seule ligne) :
 * <pre>
 * [FlightSearchDiag] route=PAR-ABJ dates=2026-11-29/2026-12-27 direct=false currency=EUR exact=0
 *   nearbyWindow=7 offers=1 similarOffers=4 stay=28 similarStay=21-35
 *   months=2026-11×2026-12{raw=312 full=false w3=0 w7=1 dep=2026-11-01..2026-11-30
 *   ret=2026-12-01..2026-12-31 stays=1/14/30}; 2026-12×2026-12{failed}
 * </pre>
 * {@code raw} : offres renvoyées par le partenaire pour ce couple de mois, avant tout filtre ;
 * {@code full} : la page a atteint {@link FlightPriceService#MONTH_LIMIT} (troncature possible) ;
 * {@code w3}/{@code w7} : offres de ce couple à 3 et 7 jours près des dates demandées ; {@code dep}/{@code ret} :
 * plus petite et plus grande date de départ / retour vues ; {@code stays} : durée de séjour en jours,
 * min/médiane/max ; {@code failed} : appel en échec. {@code nearbyWindow=-} sans repli.
 */
final class FlightSearchDiagnostic {

    static final String PREFIX = "[FlightSearchDiag]";

    private FlightSearchDiagnostic() {
    }

    /**
     * @param asked   couples de mois demandés au partenaire pendant la recherche, dans l'ordre
     * @param byMonth offres brutes par couple ; un couple demandé mais absent a échoué
     */
    static String line(FlightQuery query, int exactCount, Integer nearbyWindow, int offerCount, int similarCount,
                       Collection<FlightQuery> asked, Map<FlightQuery, List<TravelpayoutsPrice>> byMonth) {
        StringBuilder line = new StringBuilder(PREFIX)
                .append(" route=").append(query.origin()).append('-').append(query.destination())
                .append(" dates=").append(query.departure()).append('/').append(query.returnDate())
                .append(" direct=").append(query.directOnly())
                .append(" currency=").append(query.currency())
                .append(" exact=").append(exactCount)
                .append(" nearbyWindow=").append(nearbyWindow == null ? "-" : nearbyWindow)
                .append(" offers=").append(offerCount)
                .append(" similarOffers=").append(similarCount);
        if (FlightSimilarStays.applies(query)) {
            FlightSimilarStays.StayRange range = FlightSimilarStays.stayRange(query);
            line.append(" stay=").append(FlightSimilarStays.requestedStay(query))
                    .append(" similarStay=").append(range.minDays()).append('-').append(range.maxDays());
        }
        StringJoiner months = new StringJoiner("; ", " months=", "");
        months.setEmptyValue(" months=-");
        for (FlightQuery month : asked) {
            List<TravelpayoutsPrice> rows = byMonth.get(month);
            months.add(month.departure() + "×" + month.returnDate()
                    + (rows == null ? "{failed}" : "{" + stats(query, rows) + "}"));
        }
        return line.append(months).toString();
    }

    private static String stats(FlightQuery query, List<TravelpayoutsPrice> rows) {
        List<LocalDate> outs = new ArrayList<>();
        List<LocalDate> backs = new ArrayList<>();
        List<Long> stays = new ArrayList<>();
        for (TravelpayoutsPrice p : rows) {
            LocalDate out = FlightNearbyDates.localDate(p.departureAt());
            LocalDate back = FlightNearbyDates.localDate(p.returnAt());
            if (out != null) outs.add(out);
            if (back != null) backs.add(back);
            if (out != null && back != null) stays.add(ChronoUnit.DAYS.between(out, back));
        }
        return "raw=" + rows.size()
                + " full=" + (rows.size() >= FlightPriceService.MONTH_LIMIT)
                + " w3=" + FlightNearbyDates.filter(query, rows, FlightNearbyDates.WINDOW_DAYS).size()
                + " w7=" + FlightNearbyDates.filter(query, rows, FlightNearbyDates.WIDE_WINDOW_DAYS).size()
                + " dep=" + range(outs)
                + " ret=" + range(backs)
                + " stays=" + distribution(stays);
    }

    private static String range(List<LocalDate> dates) {
        if (dates.isEmpty()) return "-";
        LocalDate min = dates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate max = dates.stream().max(LocalDate::compareTo).orElseThrow();
        return min + ".." + max;
    }

    /** min/médiane/max ; médiane basse pour un nombre pair de valeurs. */
    private static String distribution(List<Long> values) {
        if (values.isEmpty()) return "-";
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(0) + "/" + sorted.get((sorted.size() - 1) / 2) + "/" + sorted.get(sorted.size() - 1);
    }
}
