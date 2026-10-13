package com.yadony.api.flights;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offres d'une alerte prix, mêmes règles pour le scheduler et l'écran « Vols trouvés » :
 * un appel par mois (ou jour) de la période ({@link FlightAlertWindow#queries}), filtre direct
 * et devise de l'alerte portés par la requête, offres hors période écartées.
 */
final class FlightAlertMatches {

    /** Codes compagnie retenus pour l'aperçu d'une alerte. */
    static final int MAX_AIRLINES = 3;

    private FlightAlertMatches() {
    }

    /**
     * Offres de la période de l'alerte, dédoublonnées (deux mois ne rendent pas deux fois la même
     * offre) et triées par prix croissant.
     *
     * @throws FlightProviderException si un des appels échoue : un résultat partiel serait faux
     */
    static List<TravelpayoutsPrice> inWindow(FlightPriceAlertEntity alert, FlightPriceService prices,
                                             LocalDate today) {
        FlightAlertWindow window = alert.window();
        Map<String, TravelpayoutsPrice> unique = new LinkedHashMap<>();
        for (FlightQuery query : window.queries(alert.getOriginCode(), alert.getDestinationCode(),
                alert.isDirectOnly(), alert.getCurrency(), today)) {
            for (TravelpayoutsPrice price : prices.prices(query)) {
                if (price.price() != null && window.accepts(price)) {
                    unique.merge(FlightPriceService.dedupKey(price), price,
                            (a, b) -> b.price().compareTo(a.price()) < 0 ? b : a);
                }
            }
        }
        List<TravelpayoutsPrice> sorted = new ArrayList<>(unique.values());
        sorted.sort(Comparator.comparing(TravelpayoutsPrice::price));
        return sorted;
    }

    /** Prix le plus bas, {@code null} sans offre (la liste est triée). */
    static BigDecimal cheapest(List<TravelpayoutsPrice> sorted) {
        return sorted.isEmpty() ? null : sorted.get(0).price();
    }

    /** Offres au prix inférieur ou égal au seuil, dans l'ordre reçu. */
    static List<TravelpayoutsPrice> underThreshold(List<TravelpayoutsPrice> sorted, BigDecimal maxPrice) {
        return sorted.stream().filter(p -> p.price().compareTo(maxPrice) <= 0).toList();
    }

    /** Au plus {@link #MAX_AIRLINES} codes compagnie distincts, dans l'ordre des offres. */
    static List<String> airlines(List<TravelpayoutsPrice> offers) {
        Set<String> codes = new LinkedHashSet<>();
        for (TravelpayoutsPrice p : offers) {
            if (codes.size() >= MAX_AIRLINES) break;
            if (p.airline() != null && !p.airline().isBlank()) {
                codes.add(p.airline().trim().toUpperCase(Locale.ROOT));
            }
        }
        return List.copyOf(codes);
    }

    /** Enregistre sur l'alerte le résultat de la vérification (remis à 0 quand plus rien n'est sous le seuil). */
    static void record(FlightPriceAlertEntity alert, List<TravelpayoutsPrice> sorted) {
        List<TravelpayoutsPrice> under = underThreshold(sorted, alert.getMaxPrice());
        alert.setLastMatchCount(under.size());
        alert.setLastMatchAirlines(join(airlines(under)));
    }

    static String join(List<String> codes) {
        return String.join(",", codes);
    }

    /** « AF,SS » → [AF, SS] ; {@code null} ou vide → liste vide. */
    static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return Arrays.stream(joined.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
