package com.yadony.api.flights;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

/**
 * Fuseau horaire d'un aéroport, ou de sa ville en repli ({@code ABJ} → {@code Africa/Abidjan}),
 * pour exprimer l'heure d'arrivée d'un vol dans l'heure locale de l'arrivée.
 *
 * <p>Même politique que {@link AirlineDirectory} : les listes Travelpayouts changent rarement,
 * une copie gardée 24 h ; injoignables ou vides, on réessaie au bout de 10 minutes et les
 * heures d'arrivée restent {@code null} entre-temps (jamais d'heure fausse).
 */
@Component
public class AirportTimeZones {

    private static final Logger log = LoggerFactory.getLogger(AirportTimeZones.class);
    static final String AIRPORTS = "airports";
    static final String CITIES = "cities";

    private final TravelpayoutsClient client;
    private final Cache<String, Map<String, String>> byKind = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(2)
            .build();
    private final Cache<String, Boolean> recentFailures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(2)
            .build();

    public AirportTimeZones(TravelpayoutsClient client) {
        this.client = client;
    }

    /**
     * @param airportCode code IATA de l'aéroport, peut être {@code null}
     * @param cityCode    code IATA de la ville, utilisé si l'aéroport est absent ou inconnu
     * @return le fuseau, ou {@code null} s'il est inconnu ou invalide
     */
    public ZoneId zoneOf(String airportCode, String cityCode) {
        ZoneId zone = lookup(AIRPORTS, airportCode);
        return zone != null ? zone : lookup(CITIES, cityCode);
    }

    private ZoneId lookup(String kind, String code) {
        if (code == null || code.isBlank()) return null;
        String id = zones(kind).get(code.trim().toUpperCase(Locale.ROOT));
        if (id == null) return null;
        try {
            return ZoneId.of(id);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private Map<String, String> zones(String kind) {
        Map<String, String> cached = byKind.getIfPresent(kind);
        if (cached != null) return cached;
        if (recentFailures.getIfPresent(kind) != null || !client.isConfigured()) return Map.of();
        try {
            Map<String, String> loaded = client.timeZones(kind);
            if (loaded.isEmpty()) {
                recentFailures.put(kind, true);
                return Map.of();
            }
            Map<String, String> copy = Map.copyOf(loaded);
            byKind.put(kind, copy);
            return copy;
        } catch (FlightProviderException e) {
            log.info("Fuseaux des {} indisponibles, heures d'arrivée non calculées", kind);
            recentFailures.put(kind, true);
            return Map.of();
        }
    }
}
