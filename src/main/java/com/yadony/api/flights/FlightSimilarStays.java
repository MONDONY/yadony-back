package com.yadony.api.flights;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * « Séjours de durée proche » d'une recherche aller-retour à jours précis qui rend peu d'offres.
 *
 * <p>Le cache d'Aviasales ne contient souvent qu'une poignée d'allers-retours aux dates exactes (ou à
 * une semaine près), alors que le même trajet existe pour un séjour de durée voisine à d'autres dates
 * (ex. 28 jours demandés : départ le 15/11, retour le 10/12). On propose ces séjours à part
 * ({@code similarOffers}) : durée à {@link #STAY_SPREAD_DAYS} jours près de celle demandée, départ le
 * mois demandé, le précédent s'il n'est pas passé, ou le suivant.
 *
 * <p>Les offres viennent des recherches au mois (mois de départ × mois de retour), celles déjà lues
 * par le repli « dates proches » d'abord, puis les plus prometteuses : voir {@link #monthQueries}.
 */
final class FlightSimilarStays {

    /** Écart maximal, en jours, entre la durée d'un séjour proposé et celle demandée. */
    static final int STAY_SPREAD_DAYS = 7;
    /** Séjours proposés au plus. */
    static final int MAX_OFFERS = 10;

    private FlightSimilarStays() {
    }

    /** Plage de durée de séjour, en jours, bornes comprises. */
    record StayRange(int minDays, int maxDays) {
        boolean contains(long days) {
            return days >= minDays && days <= maxDays;
        }
    }

    /** Aller-retour dont le départ et le retour sont des jours précis. */
    static boolean applies(FlightQuery query) {
        return query.roundTrip() && query.departure() != null && query.departure().isDay()
                && query.returnDate().isDay();
    }

    /** Durée demandée, en jours (retour - départ). */
    static int requestedStay(FlightQuery query) {
        return (int) ChronoUnit.DAYS.between(query.departure().day(), query.returnDate().day());
    }

    /** Durée demandée ± {@link #STAY_SPREAD_DAYS}, jamais négative (28 j → 21 à 35 j). */
    static StayRange stayRange(FlightQuery query) {
        int stay = requestedStay(query);
        return new StayRange(Math.max(0, stay - STAY_SPREAD_DAYS), stay + STAY_SPREAD_DAYS);
    }

    /** Mois de départ acceptés : le précédent s'il n'est pas entièrement passé, celui demandé, le suivant. */
    static Set<YearMonth> departureMonths(FlightQuery query, LocalDate today) {
        YearMonth requested = YearMonth.from(query.departure().day());
        Set<YearMonth> months = new LinkedHashSet<>();
        YearMonth previous = requested.minusMonths(1);
        if (!previous.atEndOfMonth().isBefore(today)) months.add(previous);
        months.add(requested);
        months.add(requested.plusMonths(1));
        return months;
    }

    /**
     * Les recherches au mois qui peuvent contenir un séjour proposé : chaque mois de départ accepté
     * croisé avec chaque mois où un retour peut tomber ({@code 1er du mois + durée min} au
     * {@code dernier jour + durée max}). Le couple demandé d'abord, puis par rendement attendu, décroissant : le nombre de
     * couples (jour de départ à venir, jour de retour) du couple de mois dont la durée est dans la
     * plage (à égalité, le plus proche du couple demandé d'abord). Quand le budget d'appels coupe la
     * liste, ce sont les couples qui ne peuvent contenir que quelques séjours en bord de mois qui
     * sautent. Les couples sans aucun séjour possible sont écartés.
     */
    static List<FlightQuery> monthQueries(FlightQuery query, LocalDate today) {
        StayRange range = stayRange(query);
        YearMonth wantedOut = YearMonth.from(query.departure().day());
        YearMonth wantedBack = YearMonth.from(query.returnDate().day());
        record Candidate(FlightQuery query, int pairs, long distance) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (YearMonth out : departureMonths(query, today)) {
            YearMonth firstBack = YearMonth.from(out.atDay(1).plusDays(range.minDays()));
            YearMonth lastBack = YearMonth.from(out.atEndOfMonth().plusDays(range.maxDays()));
            for (YearMonth back = firstBack; !back.isAfter(lastBack); back = back.plusMonths(1)) {
                int pairs = possibleStays(out, back, range, today);
                if (pairs == 0) continue;
                candidates.add(new Candidate(new FlightQuery(query.origin(), query.destination(),
                        FlightDate.ofMonth(out), FlightDate.ofMonth(back), query.directOnly(), query.currency()),
                        pairs, distance(wantedOut, out) + distance(wantedBack, back)));
            }
        }
        candidates.sort(Comparator.comparing((Candidate c) -> c.distance() != 0)
                .thenComparing(Comparator.comparingInt(Candidate::pairs).reversed())
                .thenComparingLong(Candidate::distance));
        return candidates.stream().map(Candidate::query).toList();
    }

    /** Nombre de couples (départ en {@code out} pas avant aujourd'hui, retour en {@code back}) de durée dans la plage. */
    static int possibleStays(YearMonth out, YearMonth back, StayRange range, LocalDate today) {
        int pairs = 0;
        for (LocalDate d = out.atDay(1); !d.isAfter(out.atEndOfMonth()); d = d.plusDays(1)) {
            if (d.isBefore(today)) continue;
            LocalDate from = max(d.plusDays(range.minDays()), back.atDay(1));
            LocalDate to = min(d.plusDays(range.maxDays()), back.atEndOfMonth());
            if (!to.isBefore(from)) pairs += (int) ChronoUnit.DAYS.between(from, to) + 1;
        }
        return pairs;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private static long distance(YearMonth a, YearMonth b) {
        return Math.abs(ChronoUnit.MONTHS.between(a, b));
    }

    /**
     * Les séjours proposés parmi {@code rows} : départ un mois accepté et pas avant aujourd'hui, durée
     * dans la plage, sans escale si la recherche ne veut que des directs, absents de {@code excludedKeys}
     * (clés {@link FlightPriceService#dedupKey} des offres déjà rendues). Dédoublonnés, triés par prix,
     * {@link #MAX_OFFERS} au plus.
     */
    static List<TravelpayoutsPrice> select(FlightQuery query, List<TravelpayoutsPrice> rows, LocalDate today,
                                           Set<String> excludedKeys) {
        StayRange range = stayRange(query);
        Set<YearMonth> months = departureMonths(query, today);
        List<TravelpayoutsPrice> kept = rows.stream()
                .filter(Objects::nonNull)
                .filter(p -> !query.directOnly() || FlightPriceService.isDirect(p))
                .filter(p -> !excludedKeys.contains(FlightPriceService.dedupKey(p)))
                .filter(p -> {
                    LocalDate out = FlightNearbyDates.localDate(p.departureAt());
                    LocalDate back = FlightNearbyDates.localDate(p.returnAt());
                    return out != null && back != null && !out.isBefore(today)
                            && months.contains(YearMonth.from(out))
                            && range.contains(ChronoUnit.DAYS.between(out, back));
                })
                .toList();
        return FlightPriceService.merge(kept).stream().limit(MAX_OFFERS).toList();
    }
}
