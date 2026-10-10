package com.yadony.api.common.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface MediaUploadRepository extends JpaRepository<MediaUploadEntity, UUID> {

    long countByOwnerIdAndCreatedAtAfter(UUID ownerId, LocalDateTime since);

    long countByBidIdAndPurpose(UUID bidId, MediaPurpose purpose);

    /** Nombre et octets des envois depuis {@code since} : carte « Stockage photos » de l'admin. */
    @Query("SELECT new com.yadony.api.common.media.UploadStats(COUNT(m), COALESCE(SUM(m.sizeBytes), 0)) "
            + "FROM MediaUploadEntity m WHERE m.createdAt > :since")
    UploadStats statsSince(@Param("since") LocalDateTime since);
}
