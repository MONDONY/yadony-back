package com.yadony.api.gppro;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface GpProApplicationRepository extends JpaRepository<GpProApplicationEntity, UUID> {

    Optional<GpProApplicationEntity> findFirstByUserIdAndStatusIn(
            UUID userId, Collection<GpProApplicationStatus> statuses);

    Optional<GpProApplicationEntity> findFirstByUserIdOrderByCreatedAtDesc(UUID userId);

    Page<GpProApplicationEntity> findByStatusOrderByCreatedAtAsc(GpProApplicationStatus status, Pageable p);

    long countByStatus(GpProApplicationStatus status);

    /**
     * Décide une demande en attente. Renvoie 1 au seul appelant qui l'a décidée : deux décisions
     * concurrentes ne s'appliquent donc qu'une fois.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE GpProApplicationEntity a
        SET a.status = :newStatus, a.decidedBy = :adminId, a.decisionReason = :reason,
            a.decidedAt = :now, a.updatedAt = :now
        WHERE a.id = :id AND a.status = com.yadony.api.gppro.GpProApplicationStatus.PENDING
          AND a.deletedAt IS NULL
    """)
    int decide(@Param("id") UUID id,
               @Param("newStatus") GpProApplicationStatus newStatus,
               @Param("adminId") UUID adminId,
               @Param("reason") String reason,
               @Param("now") LocalDateTime now);
}
