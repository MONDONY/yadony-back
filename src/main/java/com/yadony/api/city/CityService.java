package com.yadony.api.city;

import com.yadony.api.city.dto.CitySearchResponse;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class CityService {

    /** Cache déclaré dans {@code CacheConfig} : référentiel quasi statique, TTL longue. */
    static final String CACHE_NAME = "city-search";

    private static final int MAX_LIMIT = 15;
    private final CityRepository cityRepository;

    public CityService(CityRepository cityRepository) {
        this.cityRepository = cityRepository;
    }

    /**
     * Autocomplétion de villes, tirée à chaque frappe des formulaires de publication.
     * La clé de cache normalise la requête (casse, espaces) et borne la limite comme la
     * méthode le fait elle-même : « Dak »/10 et «  dak  »/50 partagent l'entrée, la
     * recherche {@code ILIKE} renvoyant de toute façon le même résultat.
     */
    @Cacheable(cacheNames = CACHE_NAME, key = "T(com.yadony.api.city.CityService).cacheKey(#query, #limit)")
    public List<CitySearchResponse> search(String query, int limit) {
        if (query == null || query.trim().length() < 2) {
            throw new IllegalArgumentException("query must have at least 2 characters");
        }
        int effectiveLimit = clampLimit(limit);
        return cityRepository.searchByName(query.trim(), effectiveLimit)
            .stream()
            .map(e -> new CitySearchResponse(
                e.getName(),
                e.getCountryCode(),
                e.getCountryName(),
                e.getLatitude().doubleValue(),
                e.getLongitude().doubleValue()
            ))
            .toList();
    }

    /** Borne la limite à [1, MAX_LIMIT] : un {@code limit} nul ou négatif partait tel quel
     *  en {@code LIMIT} SQL (liste vide ou erreur), un {@code limit} trop grand pesait sur la base. */
    static int clampLimit(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }

    /**
     * Clé de cache de {@link #search}. Publique : évaluée par SpEL depuis l'annotation.
     * Tolère une requête {@code null} pour laisser la validation de {@link #search} lever
     * l'{@link IllegalArgumentException} attendue plutôt qu'une erreur SpEL.
     */
    public static String cacheKey(String query, int limit) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return normalized + ':' + clampLimit(limit);
    }
}
