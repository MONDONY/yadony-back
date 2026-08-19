package com.yadony.api.search;

import com.yadony.api.city.CityEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Lecture directe de la table {@code cities} pour la résolution floue.
 *
 * <p>Le package {@code search} n'injecte pas {@code CityService} : la règle projet
 * interdit l'injection de service entre packages. Lire l'entité par un repository
 * propre est le même contournement que celui d'{@code AnnouncementSpecification},
 * qui interroge {@code UserEntity} en sous-requête.
 *
 * <p>{@code similarity()} vient de {@code pg_trgm}, installé par la migration V51,
 * qui a aussi posé {@code idx_cities_name_trigram}. L'extension {@code unaccent}
 * n'est pas disponible : les trigrammes absorbent déjà l'absence d'accent, à
 * condition que le token arrive en minuscules.
 */
public interface SearchCityRepository extends Repository<CityEntity, Long> {

    record CityMatch(String name, double similarity, long population) {}

    @Query(value = """
        SELECT name              AS name,
               similarity(LOWER(name), :token) AS similarity,
               population        AS population
        FROM cities
        WHERE similarity(LOWER(name), :token) >= :threshold
        ORDER BY similarity DESC, population DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<CityMatch> findSimilar(
            @Param("token") String token,
            @Param("threshold") double threshold,
            @Param("limit") int limit);
}
