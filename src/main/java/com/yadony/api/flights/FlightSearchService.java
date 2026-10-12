package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightSearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

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

        var found = fetch(query);
        if (!caller.anonymous()) remember(caller, query);
        return new FlightSearchResponse(query.origin(), query.destination(), query.roundTrip(), query.currency(),
                found.stream().map(p -> mapper.toDto(p, query.currency(), caller.language())).toList());
    }

    private java.util.List<TravelpayoutsPrice> fetch(FlightQuery query) {
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
