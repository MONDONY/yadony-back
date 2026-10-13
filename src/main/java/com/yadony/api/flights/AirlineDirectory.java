package com.yadony.api.flights;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Noms des compagnies à partir de leur code IATA ({@code U2} → easyJet).
 *
 * <p>La liste Travelpayouts change rarement : une copie par langue, gardée 24 h.
 * Si elle est injoignable, on affiche le code plutôt que de faire échouer la
 * recherche, et on réessaie au bout de 10 minutes (une liste vide n'est pas gardée 24 h).
 *
 * <p>Les compagnies des corridors Yadony ont en plus un nom de secours ({@link #CORRIDOR_AIRLINES}) :
 * Corsair s'affichait « SS » parce que la liste française n'avait pas son nom. Chaque code a été
 * vérifié dans {@code data/en/airlines.json} (attention : {@code DN} y est Dan Air, Air Sénégal est
 * {@code HC}).
 */
@Component
public class AirlineDirectory {

    private static final Logger log = LoggerFactory.getLogger(AirlineDirectory.class);

    /** Noms de secours des compagnies des corridors diaspora, si la liste n'en donne pas. */
    static final Map<String, String> CORRIDOR_AIRLINES = Map.ofEntries(
            Map.entry("AF", "Air France"),
            Map.entry("SS", "Corsair"),
            Map.entry("TO", "Transavia France"),
            Map.entry("HF", "Air Côte d'Ivoire"),
            Map.entry("HC", "Air Sénégal"),
            Map.entry("L6", "Mauritania Airlines"),
            Map.entry("2J", "Air Burkina"),
            Map.entry("KP", "ASKY Airlines"),
            Map.entry("QC", "Camair-Co"),
            Map.entry("ET", "Ethiopian Airlines"),
            Map.entry("AT", "Royal Air Maroc"),
            Map.entry("TU", "Tunisair"),
            Map.entry("AH", "Air Algérie"),
            Map.entry("SN", "Brussels Airlines"),
            Map.entry("TK", "Turkish Airlines"));

    private final TravelpayoutsClient client;
    private final Cache<String, Map<String, String>> byLocale = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(4)
            .build();
    private final Cache<String, Boolean> recentFailures = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(4)
            .build();

    public AirlineDirectory(TravelpayoutsClient client) {
        this.client = client;
    }

    /** Nom lisible de la compagnie : la liste Travelpayouts, puis le nom de secours, puis le code lui-même. */
    public String nameOf(String code, String language) {
        if (code == null || code.isBlank()) return null;
        String upper = code.trim().toUpperCase(Locale.ROOT);
        String name = names(language).get(upper);
        if (name != null) return name;
        return CORRIDOR_AIRLINES.getOrDefault(upper, upper);
    }

    private Map<String, String> names(String language) {
        String locale = "en".equalsIgnoreCase(language) ? "en" : "fr";
        Map<String, String> cached = byLocale.getIfPresent(locale);
        if (cached != null) return cached;
        if (recentFailures.getIfPresent(locale) != null || !client.isConfigured()) return Map.of();
        try {
            Map<String, String> loaded = client.airlineNames(locale);
            if (loaded.isEmpty()) {
                recentFailures.put(locale, true);
                return Map.of();
            }
            byLocale.put(locale, Map.copyOf(loaded));
            return loaded;
        } catch (FlightProviderException e) {
            log.info("Liste des compagnies indisponible ({}), codes affichés à la place", locale);
            recentFailures.put(locale, true);
            return Map.of();
        }
    }
}
