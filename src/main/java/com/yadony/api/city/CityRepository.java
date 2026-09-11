package com.yadony.api.city;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public interface CityRepository extends JpaRepository<CityEntity, Long> {

    @Query(value = """
        SELECT * FROM cities
        WHERE name ILIKE :prefix
           OR name ILIKE :anywhere
        ORDER BY
            CASE WHEN name ILIKE :prefix THEN 0 ELSE 1 END,
            population DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<CityEntity> searchByName(
        @Param("prefix")   String prefix,
        @Param("anywhere") String anywhere,
        @Param("limit")    int limit
    );

    default List<CityEntity> searchByName(String query, int limit) {
        String q = query.trim();
        return searchByName(q + "%", "%" + q + "%", limit);
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
}
