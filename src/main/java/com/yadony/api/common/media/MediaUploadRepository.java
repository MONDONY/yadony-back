package com.yadony.api.common.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface MediaUploadRepository extends JpaRepository<MediaUploadEntity, UUID> {

    /** Quota journalier : envois du propriétaire depuis {@code since}, hors {@code excluded} (le tracking). */
    long countByOwnerIdAndPurposeNotAndCreatedAtAfter(UUID ownerId, MediaPurpose excluded, LocalDateTime since);

    /** Plafond de suivi : envois du bid pour {@code purpose} depuis {@code since} (24 h glissantes). */
    long countByBidIdAndPurposeAndCreatedAtAfter(UUID bidId, MediaPurpose purpose, LocalDateTime since);

    /**
     * L'objet {@code objectKey} a été réellement téléversé par {@code ownerId} pour le colis {@code bidId} (ligne
     * inscrite par {@code StorageService.storeImage}, taille écrite après l'envoi au stockage : {@code minSize} = 0
     * exclut une réservation dont l'écriture n'a pas abouti).
     */
    boolean existsByObjectKeyAndOwnerIdAndBidIdAndPurposeAndSizeBytesGreaterThan(String objectKey, UUID ownerId,
                                                                                 UUID bidId, MediaPurpose purpose,
                                                                                 long minSize);

    /** Nombre et octets des envois depuis {@code since} : carte « Stockage photos » de l'admin. */
    @Query("SELECT new com.yadony.api.common.media.UploadStats(COUNT(m), COALESCE(SUM(m.sizeBytes), 0)) "
            + "FROM MediaUploadEntity m WHERE m.createdAt > :since")
    UploadStats statsSince(@Param("since") LocalDateTime since);
}
