package com.yadony.api.city;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface CityRepository extends JpaRepository<CityEntity, Long> {

    /**
     * Villes dont le nom, ou le nom du pays, correspond à la saisie.
     *
     * <p>Le pays compte aussi : « Niger » ne renvoyait que Wernigerode, Annigeri et
     * Ennigerloh, jamais Niamey (Sentry FLUTTER-D3/D7). Ordre : nom qui commence par la
     * saisie, puis pays dont le nom est exactement la saisie, puis nom qui la contient,
     * puis pays dont le nom commence par elle ; population à rang égal. Le pays exact
     * passe avant le simple préfixe pour que « Niger » donne Niamey avant Lagos
     * (Nigeria, plus peuplée).
     */
    @Query(value = """
        SELECT * FROM cities
        WHERE name ILIKE :prefix
           OR name ILIKE :anywhere
           OR country_name ILIKE :prefix
        ORDER BY
            CASE
                WHEN name ILIKE :prefix THEN 0
                WHEN LOWER(country_name) = LOWER(:exact) THEN 1
                WHEN name ILIKE :anywhere THEN 2
                ELSE 3
            END,
            population DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<CityEntity> searchByName(
        @Param("exact")    String exact,
        @Param("prefix")   String prefix,
        @Param("anywhere") String anywhere,
        @Param("limit")    int limit
    );

    default List<CityEntity> searchByName(String query, int limit) {
        String q = query.trim();
        return searchByName(q, q + "%", "%" + q + "%", limit);
    }

    /**
     * Fetches the highest-population city for each name in {@code names} in a single query
     * using DISTINCT ON. Names with no match are absent from the result.
     */
    @Query(value = """
        SELECT DISTINCT ON (LOWER(name)) *
        FROM cities
        WHERE LOWER(name) = ANY(:names)
        ORDER BY LOWER(name), population DESC
        """, nativeQuery = true)
    List<CityEntity> findTopByNamesIgnoreCase(@Param("names") String[] names);

    /**
     * Convenience wrapper: fetches the best city match for each requested name and returns
     * a map from lowercase-name to CityEntity. Names with no match are absent from the map.
     */
    default Map<String, CityEntity> findByNamesIgnoreCaseBatch(java.util.Collection<String> names) {
        if (names == null || names.isEmpty()) return Map.of();
        String[] lowerNames = names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::toLowerCase)
                .distinct()
                .toArray(String[]::new);
        if (lowerNames.length == 0) return Map.of();
        Map<String, CityEntity> result = new HashMap<>();
        for (CityEntity city : findTopByNamesIgnoreCase(lowerNames)) {
            result.putIfAbsent(city.getName().toLowerCase(), city);
        }
        return result;
    }

    /**
     * Ville du trajet retrouvée par nom (sans tenir compte de la casse) et code pays ;
     * la plus peuplée en cas d'homonymes.
     */
    @Query(value = """
        SELECT * FROM cities
        WHERE LOWER(name) = LOWER(:name) AND country_code = :countryCode
        ORDER BY population DESC
        LIMIT 1
        """, nativeQuery = true)
    java.util.Optional<CityEntity> findTopByNameAndCountryCode(
        @Param("name")        String name,
        @Param("countryCode") String countryCode
    );

    /**
     * Villes d'au moins {@code minPopulation} habitants dans un rectangle lat/lon, les plus
     * peuplées d'abord, plafonnées à {@code limit} pour ne jamais charger tout le référentiel.
     */
    @Query(value = """
        SELECT * FROM cities
        WHERE latitude BETWEEN :minLat AND :maxLat
          AND longitude BETWEEN :minLon AND :maxLon
          AND population >= :minPopulation
        ORDER BY population DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<CityEntity> findInBoundingBox(
        @Param("minLat")        double minLat,
        @Param("maxLat")        double maxLat,
        @Param("minLon")        double minLon,
        @Param("maxLon")        double maxLon,
        @Param("minPopulation") long minPopulation,
        @Param("limit")         int limit
    );
}
