package com.yadony.api.flights;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Prix d'un trajet, avec un cache de 30 minutes partagé par la recherche, les bons plans
 * et le scheduler d'alertes : les prix Aviasales sont eux-mêmes un cache rafraîchi au fil
 * des recherches, les redemander plus souvent ne les rendrait pas plus frais.
 */
@Service
public class FlightPriceService {

    static final int MAX_RESULTS = 30;

    private final TravelpayoutsClient client;
    private final Cache<String, List<TravelpayoutsPrice>> cache;

    public FlightPriceService(TravelpayoutsClient client) {
        this(client, Duration.ofMinutes(30));
    }

    FlightPriceService(TravelpayoutsClient client, Duration ttl) {
        this.client = client;
        this.cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(5_000).build();
    }

    public boolean isAvailable() {
        return client.isConfigured();
    }

    /**
     * @throws FlightProviderException si Travelpayouts est injoignable (jamais mis en cache)
     */
    public List<TravelpayoutsPrice> prices(FlightQuery query) {
        List<TravelpayoutsPrice> cached = cache.getIfPresent(query.key());
        if (cached != null) return cached;
        List<TravelpayoutsPrice> fresh = client.pricesForDates(query.origin(), query.destination(),
                query.departureMonth(), query.returnMonth(), query.directOnly(), query.currency(), MAX_RESULTS);
        List<TravelpayoutsPrice> valid = fresh.stream()
                .filter(p -> p.price() != null && p.price().signum() > 0)
                .toList();
        cache.put(query.key(), valid);
        return valid;
    }
}
