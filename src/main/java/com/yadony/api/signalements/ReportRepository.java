package com.yadony.api.signalements;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReportRepository extends JpaRepository<ReportEntity, UUID> {

    @Query("""
            SELECT r FROM ReportEntity r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:targetType IS NULL OR r.targetType = :targetType)
            ORDER BY r.createdAt DESC
            """)
    Page<ReportEntity> findFiltered(
            @Param("status") ReportStatus status,
            @Param("targetType") ReportTargetType targetType,
            Pageable pageable
    );

    /** Signalements visant ce compte, pour un statut donné. */
    List<ReportEntity> findByStatusAndTargetTypeAndTargetId(
            ReportStatus status, ReportTargetType targetType, UUID targetId);

    /** Signalements écrits par ce compte, pour un statut donné. */
    List<ReportEntity> findByStatusAndReporterId(ReportStatus status, UUID reporterId);
}
