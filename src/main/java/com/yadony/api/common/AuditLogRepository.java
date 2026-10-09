package com.yadony.api.common;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {

    @Query(value = """
        SELECT a.* FROM audit_log a
        WHERE (CAST(:action AS VARCHAR) IS NULL OR a.action = :action)
          AND (CAST(:entityType AS VARCHAR) IS NULL OR a.entity_type = :entityType)
          AND (CAST(:actorId AS VARCHAR) IS NULL OR a.actor_id = CAST(:actorId AS UUID))
          AND (CAST(:from AS TIMESTAMP) IS NULL OR a.created_at >= CAST(:from AS TIMESTAMP))
          AND (CAST(:to AS TIMESTAMP) IS NULL OR a.created_at <= CAST(:to AS TIMESTAMP))
        ORDER BY a.created_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM audit_log a
        WHERE (CAST(:action AS VARCHAR) IS NULL OR a.action = :action)
          AND (CAST(:entityType AS VARCHAR) IS NULL OR a.entity_type = :entityType)
          AND (CAST(:actorId AS VARCHAR) IS NULL OR a.actor_id = CAST(:actorId AS UUID))
          AND (CAST(:from AS TIMESTAMP) IS NULL OR a.created_at >= CAST(:from AS TIMESTAMP))
          AND (CAST(:to AS TIMESTAMP) IS NULL OR a.created_at <= CAST(:to AS TIMESTAMP))
        """,
        nativeQuery = true)
    Page<AuditLogEntity> findFiltered(
        @Param("action") String action,
        @Param("entityType") String entityType,
        @Param("actorId") String actorId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        Pageable pageable
    );

    /**
     * Historique d'une verification d'identite, du plus recent au plus ancien. Deux
     * identifiants : la ligne KYC, et l'utilisateur, que {@code KycService.abandonSession}
     * utilise comme entite de sa trace.
     */
    @Query("SELECT a FROM AuditLogEntity a WHERE a.entityType = 'kyc_verification' "
            + "AND a.entityId IN :entityIds ORDER BY a.createdAt DESC, a.id DESC")
    List<AuditLogEntity> findKycHistory(@Param("entityIds") Collection<UUID> entityIds, Pageable pageable);

    /**
     * Traces d'une action sur un lot d'entités, de la plus récente à la plus ancienne. Sert à
     * retrouver qui a supprimé un élément, et pourquoi, sans dupliquer ces informations dans
     * une colonne : l'audit, immuable, fait déjà foi.
     */
    List<AuditLogEntity> findByEntityTypeAndActionAndEntityIdInOrderByCreatedAtDescIdDesc(
            String entityType, String action, Collection<UUID> entityIds);

    /** Historique d'une entité, du plus ancien au plus récent (chronologie d'un paiement). */
    List<AuditLogEntity> findTop200ByEntityTypeAndEntityIdOrderByCreatedAtAscIdAsc(String entityType, UUID entityId);

    /**
     * Dernière trace d'une action sur une entité. Sert de mémoire aux limites de fréquence
     * sans colonne dédiée : l'audit, immuable, fait déjà foi (ex. demande de nouveau code
     * de retrait, au plus une par quart d'heure et par colis).
     */
    Optional<AuditLogEntity> findFirstByEntityTypeAndEntityIdAndActionOrderByCreatedAtDescIdDesc(
            String entityType, UUID entityId, String action);
}
