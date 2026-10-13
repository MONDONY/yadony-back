package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightOfferDto;
import com.yadony.api.flights.dto.FlightSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recherche de vols de l'utilisateur. Volontairement sans {@code @Transactional} : aucune
 * connexion JDBC n'est tenue pendant l'appel à Travelpayouts.
 */
@Service
public class FlightSearchService {

    private static final Logger log = LoggerFactory.getLogger(FlightSearchService.class);

    /**
     * Sous ce nombre d'offres, une recherche aller-retour à jours précis propose des séjours de durée
     * proche et journalise sa ligne de diagnostic.
     */
    static final int FEW_OFFERS = 5;
    /** Recherches au mois (couples mois de départ × mois de retour) au plus par recherche. */
    static final int MAX_MONTH_QUERIES = 6;

    private final FlightPriceService prices;
    private final FlightOfferMapper mapper;
    private final FlightUsers users;
    private final FlightSearchRepository searches;
    private final Clock clock;
    private final boolean diagnosticEnabled;

    @Autowired
    public FlightSearchService(FlightPriceService prices, FlightOfferMapper mapper, FlightUsers users,
                               FlightSearchRepository searches,
                               @Value("${yadony.flights.search.diagnostic.enabled:false}") boolean diagnosticEnabled) {
        this(prices, mapper, users, searches, Clock.systemUTC(), diagnosticEnabled);
    }

    FlightSearchService(FlightPriceService prices, FlightOfferMapper mapper, FlightUsers users,
                        FlightSearchRepository searches, Clock clock) {
        this(prices, mapper, users, searches, clock, false);
    }

    FlightSearchService(FlightPriceService prices, FlightOfferMapper mapper, FlightUsers users,
                        FlightSearchRepository searches, Clock clock, boolean diagnosticEnabled) {
        this.prices = prices;
        this.mapper = mapper;
        this.users = users;
        this.searches = searches;
        this.clock = clock;
        this.diagnosticEnabled = diagnosticEnabled;
    }

    public FlightSearchResponse search(String firebaseUid, String origin, String destination,
                                       String departure, String returnDate,
                                       boolean directOnly, String currency) {
        if (!prices.isAvailable()) throw FlightErrors.unavailable();
        FlightQuery query = FlightQuery.of(origin, destination, departure, returnDate,
                directOnly, currency, clock);
        FlightUsers.Caller caller = users.resolve(firebaseUid);
        LocalDate today = LocalDate.now(clock);

        List<TravelpayoutsPrice> exact = fetch(query);
        List<TravelpayoutsPrice> found = exact;
        MonthReads reads = new MonthReads();
        Nearby nearby = null;
        if (found.isEmpty() && query.hasExactDay()) {
            nearby = nearbyDates(query, reads, today);
            if (nearby != null) found = nearby.offers();
        }
        List<TravelpayoutsPrice> similar = List.of();
        boolean fewRoundTrip = FlightSimilarStays.applies(query) && found.size() < FEW_OFFERS;
        if (fewRoundTrip) {
            similar = similarStays(query, found, reads, today);
            if (diagnosticEnabled) diagnose(query, exact.size(), nearby, found.size(), similar.size(), reads);
        }
        if (!caller.anonymous()) remember(caller, query);
        FlightSimilarStays.StayRange range = similar.isEmpty() ? null : FlightSimilarStays.stayRange(query);
        return new FlightSearchResponse(query.origin(), query.destination(), query.roundTrip(), query.currency(),
                toDtos(found, query, caller),
                query.searchPath(today),
                nearby != null ? Boolean.TRUE : null,
                nearby != null ? nearby.window() : null,
                similar.isEmpty() ? null : toDtos(similar, query, caller),
                range != null ? range.minDays() : null,
                range != null ? range.maxDays() : null);
    }

    private List<FlightOfferDto> toDtos(List<TravelpayoutsPrice> rows, FlightQuery query, FlightUsers.Caller caller) {
        return rows.stream().map(p -> mapper.toDto(p, query.currency(), caller.language())).toList();
    }

    /** Offres du repli et fenêtre, en jours, qui les a trouvées. */
    private record Nearby(List<TravelpayoutsPrice> offers, int window) {
    }

    /**
     * Recherches au mois lues pendant une recherche : chaque couple de mois n'est demandé qu'une fois
     * (le repli à 7 jours relit ceux du repli à 3 jours, les séjours proches relisent les deux), et
     * leur nombre est plafonné à {@link #MAX_MONTH_QUERIES}. Un couple demandé mais absent de
     * {@code rows} a échoué.
     */
    private final class MonthReads {
        final Set<FlightQuery> asked = new LinkedHashSet<>();
        final Map<FlightQuery, List<TravelpayoutsPrice>> rows = new LinkedHashMap<>();

        /** Lit les couples pas encore demandés, dans l'ordre, tant que le budget le permet. */
        void read(List<FlightQuery> monthQueries) {
            List<FlightQuery> missing = new ArrayList<>();
            for (FlightQuery q : monthQueries) {
                if (asked.size() + missing.size() >= MAX_MONTH_QUERIES) break;
                if (!asked.contains(q) && !missing.contains(q)) missing.add(q);
            }
            if (missing.isEmpty()) return;
            asked.addAll(missing);
            rows.putAll(prices.pricesByMonth(missing));
        }

        List<TravelpayoutsPrice> rowsOf(List<FlightQuery> monthQueries) {
            List<TravelpayoutsPrice> all = new ArrayList<>();
            monthQueries.forEach(q -> all.addAll(rows.getOrDefault(q, List.of())));
            return all;
        }

        List<TravelpayoutsPrice> all() {
            List<TravelpayoutsPrice> all = new ArrayList<>();
            rows.values().forEach(all::addAll);
            return all;
        }
    }

    /**
     * Aucune offre aux jours exacts : même trajet relu sur chaque mois touché par la fenêtre
     * (départ × retour, voir {@link FlightNearbyDates#monthQueries}), filtré à
     * {@link FlightNearbyDates#WINDOW_DAYS} jours près sur l'aller ET le retour, puis à
     * {@link FlightNearbyDates#WIDE_WINDOW_DAYS} si rien. Offres dédoublonnées et triées par prix.
     * Les recherches au mois passent par le cache des prix et la seconde fenêtre ne redemande pas
     * les couples de la première. Un échec du repli n'est pas une erreur : la liste reste vide.
     *
     * @return {@code null} si aucune fenêtre ne trouve d'offre
     */
    private Nearby nearbyDates(FlightQuery query, MonthReads reads, LocalDate today) {
        for (int window : FlightNearbyDates.WINDOWS) {
            List<FlightQuery> months = FlightNearbyDates.monthQueries(query, window, today);
            reads.read(months);
            List<TravelpayoutsPrice> kept = FlightPriceService.merge(
                    FlightNearbyDates.filter(query, reads.rowsOf(months), window));
            if (!kept.isEmpty()) return new Nearby(kept, window);
        }
        log.info("Vols {} : aucune offre à {} jours près dans le cache du partenaire",
                query.key(), FlightNearbyDates.WIDE_WINDOW_DAYS);
        return null;
    }

    /**
     * Séjours de durée proche (voir {@link FlightSimilarStays}) : réutilise les mois déjà lus et
     * complète, par ordre de proximité, dans la limite de {@link #MAX_MONTH_QUERIES}. Bonus : un
     * échec rend une liste vide, jamais une erreur.
     */
    private List<TravelpayoutsPrice> similarStays(FlightQuery query, List<TravelpayoutsPrice> offers,
                                                  MonthReads reads, LocalDate today) {
        try {
            reads.read(FlightSimilarStays.monthQueries(query, today));
            Set<String> excluded = new HashSet<>();
            offers.forEach(p -> excluded.add(FlightPriceService.dedupKey(p)));
            return FlightSimilarStays.select(query, reads.all(), today, excluded);
        } catch (RuntimeException e) {
            log.warn("Vols {} : séjours de durée proche indisponibles ({})", query.key(), e.getClass().getSimpleName());
            return List.of();
        }
    }

    /** Ligne {@link FlightSearchDiagnostic} au niveau WARN (Sentry Logs ne garde que warn et error). */
    private void diagnose(FlightQuery query, int exactCount, Nearby nearby, int offerCount, int similarCount,
                          MonthReads reads) {
        try {
            log.warn("{}", FlightSearchDiagnostic.line(query, exactCount, nearby == null ? null : nearby.window(),
                    offerCount, similarCount, reads.asked, reads.rows));
        } catch (RuntimeException e) {
            log.warn("{} indisponible ({})", FlightSearchDiagnostic.PREFIX, e.getClass().getSimpleName());
        }
    }

    private List<TravelpayoutsPrice> fetch(FlightQuery query) {
        try {
            return prices.prices(query);
        } catch (FlightProviderException e) {
            throw FlightErrors.providerError();
        }
    }

    /** Mémorise le trajet pour les bons plans. Un échec ici ne doit jamais faire échouer la recherche. */
    private void remember(FlightUsers.Caller caller, FlightQuery query) {
        try {
            FlightSearchEntity search = new FlightSearchEntity();
            search.setOwnerId(caller.id());
            search.setOriginCode(query.origin());
            search.setDestinationCode(query.destination());
            search.setRoundTrip(query.roundTrip());
            searches.save(search);
        } catch (RuntimeException e) {
            log.warn("Recherche de vols non mémorisée : {}", e.getMessage());
        }
    }
}
