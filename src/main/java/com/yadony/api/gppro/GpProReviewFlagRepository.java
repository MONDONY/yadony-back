package com.yadony.api.gppro;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
