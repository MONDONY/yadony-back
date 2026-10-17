package com.yadony.api.gppro;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface GpProReviewFlagRepository extends JpaRepository<GpProReviewFlagEntity, UUID> {

    List<GpProReviewFlagEntity> findByUserIdAndResolvedAtIsNull(UUID userId);

    Page<GpProReviewFlagEntity> findByResolvedAtIsNullAndDismissedAtIsNull(Pageable p);

    /** Nombre de personnes à réexaminer (un signalement actif par motif, donc distinct par personne). */
    @Query("""
        SELECT count(distinct f.userId) FROM GpProReviewFlagEntity f
        WHERE f.resolvedAt IS NULL AND f.dismissedAt IS NULL
    """)
    long countDistinctUserIdByResolvedAtIsNullAndDismissedAtIsNull();

    /** Personnes à réexaminer, la plus anciennement signalée d'abord (une ligne par personne). */
    @Query(value = """
        SELECT f.userId FROM GpProReviewFlagEntity f
        WHERE f.resolvedAt IS NULL AND f.dismissedAt IS NULL
        GROUP BY f.userId ORDER BY min(f.detectedAt), f.userId
    """, countQuery = """
        SELECT count(distinct f.userId) FROM GpProReviewFlagEntity f
        WHERE f.resolvedAt IS NULL AND f.dismissedAt IS NULL
    """)
    Page<UUID> findReviewUserIds(Pageable p);

    List<GpProReviewFlagEntity> findByUserIdInAndResolvedAtIsNullAndDismissedAtIsNull(java.util.Collection<UUID> userIds);

    /** RGPD : clôt les signalements de revue encore ouverts d'un compte finalisé. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE gp_pro_review_flags SET resolved_at = :now WHERE user_id = :userId "
            + "AND resolved_at IS NULL", nativeQuery = true)
    int resolveOpenByUserId(@Param("userId") java.util.UUID userId, @Param("now") java.time.LocalDateTime now);
}
