package com.yadony.api.signalements;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ReportPhotoRepository extends JpaRepository<ReportPhotoEntity, UUID> {

    List<ReportPhotoEntity> findByReportIdOrderByCreatedAtAsc(UUID reportId);

    List<ReportPhotoEntity> findByReportIdInOrderByCreatedAtAsc(Collection<UUID> reportIds);

    /** Photos vivantes des signalements clos (RESOLVED/DISMISSED) dont la clôture précède {@code cutoff}. */
    @Query("""
            SELECT p FROM ReportPhotoEntity p, ReportEntity r
            WHERE r.id = p.reportId
              AND r.status <> com.yadony.api.signalements.ReportStatus.OPEN
              AND r.resolvedAt IS NOT NULL AND r.resolvedAt < :cutoff
            ORDER BY r.resolvedAt ASC
            """)
    List<ReportPhotoEntity> findLiveOfClosedReportsBefore(@Param("cutoff") OffsetDateTime cutoff, Pageable page);
}
