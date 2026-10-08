package com.yadony.api.ratings;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RatingRepository extends JpaRepository<RatingEntity, UUID> {

    Optional<RatingEntity> findByBidIdAndRaterId(UUID bidId, UUID raterId);

    Optional<RatingEntity> findByBidIdAndTrackingToken(UUID bidId, String trackingToken);

    List<RatingEntity> findByRatedUserId(UUID ratedUserId);

    Page<RatingEntity> findByRatedUserId(UUID ratedUserId, Pageable pageable);

    @Query("SELECT r FROM RatingEntity r WHERE r.ratedUserId = :userId AND r.excludedFromAverage = false")
    List<RatingEntity> findIncludedRatingsByRatedUserId(@Param("userId") UUID userId);

    /**
     * Distribution des notes prises en compte (mêmes lignes que
     * {@link #findIncludedRatingsByRatedUserId}) : une ligne {@code [stars, count]} par
     * valeur présente. Évite de charger toutes les notes d'un profil pour les compter.
     */
    @Query("SELECT r.stars, COUNT(r) FROM RatingEntity r "
            + "WHERE r.ratedUserId = :userId AND r.excludedFromAverage = false GROUP BY r.stars")
    List<Object[]> countIncludedByStars(@Param("userId") UUID userId);

    // Last 5 non-excluded ratings ordered by creation date — used for Kilo Pro average check
    @Query("SELECT r FROM RatingEntity r WHERE r.ratedUserId = :userId AND r.excludedFromAverage = false ORDER BY r.createdAt DESC")
    List<RatingEntity> findRecentIncludedRatings(@Param("userId") UUID userId);

    // Farming detection: ratings by same rater to same traveler in last 30 days
    @Query("SELECT r FROM RatingEntity r WHERE r.raterId = :raterId AND r.ratedUserId = :ratedUserId AND r.createdAt >= :since")
    List<RatingEntity> findByRaterIdAndRatedUserIdSince(@Param("raterId") UUID raterId,
                                                        @Param("ratedUserId") UUID ratedUserId,
                                                        @Param("since") LocalDateTime since);

    boolean existsByBidIdAndRaterId(UUID bidId, UUID raterId);

    boolean existsByBidIdAndTrackingToken(UUID bidId, String trackingToken);

    /**
     * Le destinataire de ce colis a-t-il déjà noté le voyageur (FLUTTER-CA) ?
     *
     * <p>Une seule note destinataire par colis, quel que soit le chemin : anonyme par le
     * lien de suivi ({@code tracking_token} seul), ou depuis le compte du destinataire
     * confirmé ({@code rater_id} et {@code tracking_token} renseignés). Les deux portent le
     * jeton du colis : l'index unique {@code idx_ratings_bid_tracking_token} (V27) empêche
     * donc la double note, et la moyenne du voyageur ne compte le destinataire qu'une fois.
     */
    default boolean recipientHasRated(UUID bidId, UUID recipientUserId, String trackingToken) {
        return existsByBidIdAndRaterId(bidId, recipientUserId)
                || (trackingToken != null && existsByBidIdAndTrackingToken(bidId, trackingToken));
    }

    /** Notes émises par ce compte — celles qui resteront affichées chez les autres. */
    long countByRaterId(UUID raterId);

    @Query("""
            SELECT r FROM RatingEntity r
            WHERE (:flagged IS NULL OR r.flagged = :flagged)
              AND (:minScore IS NULL OR r.stars >= :minScore)
              AND (:maxScore IS NULL OR r.stars <= :maxScore)
            ORDER BY r.createdAt DESC
            """)
    Page<RatingEntity> findAdminFiltered(
            @Param("flagged") Boolean flagged,
            @Param("minScore") Integer minScore,
            @Param("maxScore") Integer maxScore,
            Pageable pageable);

    /**
     * Avis supprimés (soft delete) pour la corbeille admin, du plus récemment supprimé au plus
     * ancien. Requête native délibérée : une requête JPQL subirait le
     * {@code @Where(deleted_at IS NULL)} de {@link RatingEntity} et ne renverrait jamais rien.
     * Le tri est porté par la requête : passer un {@code Pageable} non trié.
     */
    @Query(value = """
            SELECT r.* FROM ratings r
            WHERE r.deleted_at IS NOT NULL
              AND (CAST(:flagged AS BOOLEAN) IS NULL OR r.flagged = CAST(:flagged AS BOOLEAN))
              AND (CAST(:minScore AS INTEGER) IS NULL OR r.stars >= CAST(:minScore AS INTEGER))
              AND (CAST(:maxScore AS INTEGER) IS NULL OR r.stars <= CAST(:maxScore AS INTEGER))
            ORDER BY r.deleted_at DESC, r.id
            """,
            countQuery = """
            SELECT COUNT(*) FROM ratings r
            WHERE r.deleted_at IS NOT NULL
              AND (CAST(:flagged AS BOOLEAN) IS NULL OR r.flagged = CAST(:flagged AS BOOLEAN))
              AND (CAST(:minScore AS INTEGER) IS NULL OR r.stars >= CAST(:minScore AS INTEGER))
              AND (CAST(:maxScore AS INTEGER) IS NULL OR r.stars <= CAST(:maxScore AS INTEGER))
            """,
            nativeQuery = true)
    Page<RatingEntity> findDeletedAdminFiltered(
            @Param("flagged") Boolean flagged,
            @Param("minScore") Integer minScore,
            @Param("maxScore") Integer maxScore,
            Pageable pageable);

    /** Avis par id, supprimé ou non (même motif que {@link #findDeletedAdminFiltered}). */
    @Query(value = "SELECT * FROM ratings WHERE id = :id", nativeQuery = true)
    Optional<RatingEntity> findByIdIncludingDeleted(@Param("id") UUID id);
}
