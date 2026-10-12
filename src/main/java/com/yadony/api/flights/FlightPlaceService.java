package com.yadony.api.flights;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.flights.dto.FlightPlaceDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Villes ↔ codes IATA, via l'autocomplétion Travelpayouts.
 *
 * <p>Deux usages : l'autocomplétion des champs Départ / Arrivée de l'app, et la
 * conversion des corridors Yadony (« Abidjan ») en codes pour les bons plans. Chaque
 * ville convertie est gardée en base ({@link FlightCityCodeEntity}).
 */
@Service
public class FlightPlaceService {

    private static final Logger log = LoggerFactory.getLogger(FlightPlaceService.class);
    static final int MIN_TERM_LENGTH = 2;
    private static final int MAX_SUGGESTIONS = 8;

    private final TravelpayoutsClient client;
    private final FlightCityCodeRepository cityCodes;
    private final Cache<String, List<FlightPlaceDto>> suggestions = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(2_000)
            .build();

    public FlightPlaceService(TravelpayoutsClient client, FlightCityCodeRepository cityCodes) {
        this.client = client;
        this.cityCodes = cityCodes;
    }

    public List<FlightPlaceDto> suggest(String term, String language) {
        if (!client.isConfigured()) throw FlightErrors.unavailable();
        String trimmed = term == null ? "" : term.trim();
        if (trimmed.length() < MIN_TERM_LENGTH) return List.of();
        String locale = "en".equalsIgnoreCase(language) ? "en" : "fr";
        String cacheKey = locale + "|" + normalize(trimmed);
        List<FlightPlaceDto> cached = suggestions.getIfPresent(cacheKey);
        if (cached != null) return cached;
        List<TravelpayoutsPlace> places;
        try {
            places = client.places(trimmed, locale);
        } catch (FlightProviderException e) {
            throw FlightErrors.providerError();
        }
        List<FlightPlaceDto> result = places.stream()
                .filter(p -> p.code() != null && p.code().length() == 3 && p.name() != null)
                .limit(MAX_SUGGESTIONS)
                .map(p -> new FlightPlaceDto(p.code().toUpperCase(Locale.ROOT), p.name(),
                        p.countryCode(), p.countryName()))
                .toList();
        suggestions.put(cacheKey, result);
        return result;
    }

    /**
     * Code IATA d'une ville saisie dans Yadony, vide si aucune ville ne correspond ou si
     * Travelpayouts est injoignable (dans ce cas rien n'est mémorisé : on réessaiera).
     */
    public Optional<String> resolveCity(String cityName) {
        if (cityName == null || cityName.isBlank()) return Optional.empty();
        String key = normalize(cityName);
        Optional<FlightCityCodeEntity> known = cityCodes.findByCityKey(key);
        if (known.isPresent()) return Optional.ofNullable(known.get().getIataCode());
        if (!client.isConfigured()) return Optional.empty();

        TravelpayoutsPlace match;
        try {
            match = client.places(cityName.trim(), "fr").stream()
                    .filter(p -> "city".equalsIgnoreCase(p.type()) || p.type() == null)
                    .filter(p -> p.code() != null && p.code().length() == 3)
                    .findFirst()
                    .orElse(null);
        } catch (FlightProviderException e) {
            return Optional.empty();
        }
        FlightCityCodeEntity entity = new FlightCityCodeEntity();
        entity.setCityKey(key);
        entity.setCityName(cityName.trim());
        if (match != null) {
            entity.setIataCode(match.code().toUpperCase(Locale.ROOT));
            entity.setCountryCode(match.countryCode());
        }
        try {
            cityCodes.save(entity);
        } catch (DataIntegrityViolationException e) {
            // Une requête concurrente a enregistré la même ville : son résultat vaut le nôtre.
            log.debug("Ville déjà mémorisée en parallèle : {}", key);
        }
        return Optional.ofNullable(entity.getIataCode());
    }

    /** Minuscules, sans accents, espaces réduits : « Saint-Louis » = « saint-louis ». */
    static String normalize(String raw) {
        String noAccents = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return noAccents.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
