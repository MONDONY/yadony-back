package com.yadony.api.flights;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Corridors d'un utilisateur, lus en SQL natif sur les tables des autres packages
 * (même motif que {@code JpaToolsCompletionQueryRepository}) : lecture seule, aucun
 * service d'un autre package n'est injecté.
 */
@Repository
@Transactional(readOnly = true)
public class FlightCorridorQueryRepository {

    /** Paire de villes telle que saisie dans Yadony (trajet, demande ou alerte corridor). */
    public record CityPair(String departureCity, String arrivalCity) {
    }

    /** Paire de codes IATA déjà connue (recherche de vols). */
    public record CodePair(String origin, String destination) {
    }

    private final EntityManager em;

    public FlightCorridorQueryRepository(EntityManager em) {
        this.em = em;
    }

    /**
     * Corridors distincts de ses trajets publiés, de ses demandes d'envoi et de ses alertes
     * corridor, du plus récemment utilisé au plus ancien.
     */
    public List<CityPair> recentCityCorridors(UUID userId, int limit) {
        String sql = """
                SELECT departure_city, arrival_city, MAX(created_at) AS last_at FROM (
                    SELECT departure_city, arrival_city, created_at FROM announcements
                     WHERE traveler_id = :id AND deleted_at IS NULL
                    UNION ALL
                    SELECT departure_city, arrival_city, created_at FROM package_requests
                     WHERE sender_id = :id AND deleted_at IS NULL
                    UNION ALL
                    SELECT departure_city, arrival_city, created_at FROM corridor_alerts
                     WHERE owner_id = :id AND deleted_at IS NULL
                ) corridors
                GROUP BY departure_city, arrival_city
                ORDER BY last_at DESC
                """;
        List<?> rows = em.createNativeQuery(sql).setParameter("id", userId).setMaxResults(limit).getResultList();
        List<CityPair> pairs = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] cols = (Object[]) row;
            if (cols[0] != null && cols[1] != null) {
                pairs.add(new CityPair(cols[0].toString(), cols[1].toString()));
            }
        }
        return pairs;
    }

    /** Trajets aériens cherchés par l'utilisateur, du plus récent au plus ancien. */
    public List<CodePair> recentSearchedRoutes(UUID userId, int limit) {
        String sql = """
                SELECT origin_code, destination_code, MAX(created_at) AS last_at FROM flight_searches
                 WHERE owner_id = :id AND deleted_at IS NULL
                 GROUP BY origin_code, destination_code
                 ORDER BY last_at DESC
                """;
        List<?> rows = em.createNativeQuery(sql).setParameter("id", userId).setMaxResults(limit).getResultList();
        List<CodePair> pairs = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object[] cols = (Object[]) row;
            pairs.add(new CodePair(cols[0].toString(), cols[1].toString()));
        }
        return pairs;
    }
}
