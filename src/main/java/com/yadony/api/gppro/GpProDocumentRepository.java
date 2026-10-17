package com.yadony.api.gppro;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface GpProDocumentRepository extends JpaRepository<GpProDocumentEntity, UUID> {

    List<GpProDocumentEntity> findByApplicationIdAndPurgedAtIsNull(UUID applicationId);

    /** Documents téléversés par l'utilisateur et pas encore rattachés à une demande. */
    long countByOwnerIdAndApplicationIdIsNullAndPurgedAtIsNull(UUID ownerId);

    List<GpProDocumentEntity> findByOwnerIdAndPurgedAtIsNull(UUID ownerId);

    /** Rapprochement R2 : toutes les clés, lignes supprimées logiquement et non rattachées comprises. */
    @Query(value = "SELECT storage_key FROM gp_pro_documents WHERE storage_key IS NOT NULL "
            + "AND (storage_key LIKE CONCAT(:prefix, '%') OR storage_key LIKE CONCAT('%/', :prefix, '%'))",
            nativeQuery = true)
    List<String> findStorageKeysMatchingIncludingDeleted(@Param("prefix") String prefix);

    List<GpProDocumentEntity> findByIdInAndOwnerIdAndApplicationIdIsNull(Collection<UUID> ids, UUID ownerId);

    /** Documents jamais rattachés à une demande et non purgés, créés avant {@code cutoff}. */
    @Query("""
        SELECT d FROM GpProDocumentEntity d
        WHERE d.applicationId IS NULL AND d.purgedAt IS NULL AND d.createdAt < :cutoff
    """)
    List<GpProDocumentEntity> findOrphansCreatedBefore(@Param("cutoff") LocalDateTime cutoff);

    /** Documents non purgés dont la demande est décidée (statut hors demandes ouvertes). */
    @Query("""
        SELECT d FROM GpProDocumentEntity d
        WHERE d.purgedAt IS NULL
          AND d.applicationId IN (
              SELECT a.id FROM GpProApplicationEntity a
              WHERE a.status NOT IN (com.yadony.api.gppro.GpProApplicationStatus.AWAITING_KYC,
                                     com.yadony.api.gppro.GpProApplicationStatus.PENDING))
    """)
    List<GpProDocumentEntity> findUnpurgedOfDecidedApplications();
}
