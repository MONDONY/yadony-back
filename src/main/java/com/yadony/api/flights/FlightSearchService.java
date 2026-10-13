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
        boolean nearby = false;
        if (found.isEmpty() && query.hasExactDay()) {
            List<TravelpayoutsPrice> close = nearbyDates(query);
            if (!close.isEmpty()) {
                found = close;
                nearby = true;
            }
        }
        if (!caller.anonymous()) remember(caller, query);
        return new FlightSearchResponse(query.origin(), query.destination(), query.roundTrip(), query.currency(),
                found.stream().map(p -> mapper.toDto(p, query.currency(), caller.language())).toList(),
                query.searchPath(LocalDate.now(clock)),
                nearby ? Boolean.TRUE : null,
                nearby ? FlightNearbyDates.WINDOW_DAYS : null);
    }

    /**
     * Aucune offre aux jours exacts : même trajet sur les mois de ces jours, filtré à
     * {@link FlightNearbyDates#WINDOW_DAYS} jours près. Les deux recherches passent par le cache des
     * prix : une même requête répétée ne rappelle pas Travelpayouts. Un échec du repli n'est pas une
     * erreur : la recherche exacte a répondu, elle rend simplement sa liste vide.
     */
    private List<TravelpayoutsPrice> nearbyDates(FlightQuery query) {
        try {
            return FlightNearbyDates.filter(query, prices.prices(query.widenedToMonths()),
                    FlightPriceService.MAX_RESULTS);
        } catch (FlightProviderException e) {
            log.info("Vols {} : repli sur les dates proches indisponible ({})", query.key(), e.getMessage());
            return List.of();
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
