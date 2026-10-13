package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

/**
 * Recherche de vols de l'utilisateur. Volontairement sans {@code @Transactional} : aucune
 * connexion JDBC n'est tenue pendant l'appel à Travelpayouts.
 */
@Service
public class FlightSearchService {

    private static final Logger log = LoggerFactory.getLogger(FlightSearchService.class);

    private final FlightPriceService prices;
    private final FlightOfferMapper mapper;
    private final FlightUsers users;
    private final FlightSearchRepository searches;
    private final Clock clock;

    @Autowired
    public FlightSearchService(FlightPriceService prices, FlightOfferMapper mapper, FlightUsers users,
                               FlightSearchRepository searches) {
        this(prices, mapper, users, searches, Clock.systemUTC());
    }

    FlightSearchService(FlightPriceService prices, FlightOfferMapper mapper, FlightUsers users,
                        FlightSearchRepository searches, Clock clock) {
        this.prices = prices;
        this.mapper = mapper;
        this.users = users;
        this.searches = searches;
        this.clock = clock;
    }

    public FlightSearchResponse search(String firebaseUid, String origin, String destination,
                                       String departure, String returnDate,
                                       boolean directOnly, String currency) {
        if (!prices.isAvailable()) throw FlightErrors.unavailable();
        FlightQuery query = FlightQuery.of(origin, destination, departure, returnDate,
                directOnly, currency, clock);
        FlightUsers.Caller caller = users.resolve(firebaseUid);

        List<TravelpayoutsPrice> found = fetch(query);
        Nearby nearby = null;
        if (found.isEmpty() && query.hasExactDay()) {
            nearby = nearbyDates(query);
            if (nearby != null) found = nearby.offers();
        }
        if (!caller.anonymous()) remember(caller, query);
        return new FlightSearchResponse(query.origin(), query.destination(), query.roundTrip(), query.currency(),
                found.stream().map(p -> mapper.toDto(p, query.currency(), caller.language())).toList(),
                query.searchPath(LocalDate.now(clock)),
                nearby != null ? Boolean.TRUE : null,
                nearby != null ? nearby.window() : null);
    }

    /** Offres du repli et fenêtre, en jours, qui les a trouvées. */
    private record Nearby(List<TravelpayoutsPrice> offers, int window) {
    }

    /**
     * Aucune offre aux jours exacts : même trajet relu sur chaque mois touché par la fenêtre
     * (départ × retour, voir {@link FlightNearbyDates#monthQueries}), filtré à
     * {@link FlightNearbyDates#WINDOW_DAYS} jours près sur l'aller ET le retour, puis à
     * {@link FlightNearbyDates#WIDE_WINDOW_DAYS} si rien. Offres dédoublonnées et triées par prix.
     * Les recherches au mois passent par le cache des prix (la seconde fenêtre relit surtout le
     * cache de la première). Un échec du repli n'est pas une erreur : la liste reste vide.
     *
     * @return {@code null} si aucune fenêtre ne trouve d'offre
     */
    private Nearby nearbyDates(FlightQuery query) {
        LocalDate today = LocalDate.now(clock);
        for (int window : FlightNearbyDates.WINDOWS) {
            List<TravelpayoutsPrice> rows = prices.pricesInMonths(FlightNearbyDates.monthQueries(query, window, today));
            List<TravelpayoutsPrice> kept = FlightPriceService.merge(FlightNearbyDates.filter(query, rows, window));
            if (!kept.isEmpty()) return new Nearby(kept, window);
        }
        log.info("Vols {} : aucune offre à {} jours près dans le cache du partenaire",
                query.key(), FlightNearbyDates.WIDE_WINDOW_DAYS);
        return null;
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
