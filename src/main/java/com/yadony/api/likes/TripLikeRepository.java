package com.yadony.api.likes;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TripLikeRepository extends JpaRepository<TripLikeEntity, UUID> {

    boolean existsByUserIdAndTripId(UUID userId, UUID tripId);

    /**
     * Ajoute le j'aime s'il n'existe pas, de façon atomique ({@code ON CONFLICT DO NOTHING}
     * couvre l'index unique partiel {@code ux_trip_likes_active}) : deux touchers simultanés
     * ne lèvent pas de violation d'unicité.
     *
     * @return 1 si la ligne a été insérée, 0 si le j'aime existait déjà
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO trip_likes (id, user_id, trip_id, created_at, updated_at)
            VALUES (:id, :userId, :tripId, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("userId") UUID userId, @Param("tripId") UUID tripId);

    /**
     * Supprime physiquement le j'aime, en une requête : idempotent sous concurrence.
     *
     * @return le nombre de lignes supprimées (0 ou 1)
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM trip_likes WHERE user_id = :userId AND trip_id = :tripId", nativeQuery = true)
    int deleteActive(@Param("userId") UUID userId, @Param("tripId") UUID tripId);

    /** Nombre de j'aime d'un trajet, hors comptes supprimés. */
    @Query(value = """
            SELECT COUNT(*) FROM trip_likes l
            JOIN users u ON u.id = l.user_id AND u.deleted_at IS NULL
            WHERE l.trip_id = :tripId AND l.deleted_at IS NULL
            """, nativeQuery = true)
    long countActive(@Param("tripId") UUID tripId);

    /**
     * Lignes {@code [tripId, count]}, sans les trajets à zéro ni les comptes supprimés.
     * JPQL et non natif : l'identifiant revient typé {@link UUID} sur tous les moteurs.
     */
    @Query("""
            SELECT l.tripId, COUNT(l) FROM TripLikeEntity l, UserEntity u
            WHERE u.id = l.userId AND u.deletedAt IS NULL AND l.tripId IN :ids
            GROUP BY l.tripId
            """)
    List<Object[]> countByTripIds(@Param("ids") Collection<UUID> ids);

    @Query("SELECT l.tripId FROM TripLikeEntity l WHERE l.userId = :userId AND l.tripId IN :ids")
    List<UUID> findLikedTripIds(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);
}
