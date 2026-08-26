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
}
