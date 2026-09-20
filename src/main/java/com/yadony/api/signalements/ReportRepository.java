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

    /**
     * Même filtre + recherche libre {@code q} (déjà en minuscules, entouré de %) sur la
     * description, la route d'écran et le nom du signalant, ou sur les motifs dont le code
     * ou le libellé contient le texte ({@code reasons}, calculés côté service ;
     * {@code hasReasons} évite un IN vide).
     */
    @Query("""
            SELECT r FROM ReportEntity r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:targetType IS NULL OR r.targetType = :targetType)
              AND (
                   lower(coalesce(r.description, '')) LIKE :q
                OR lower(coalesce(r.screenRoute, '')) LIKE :q
                OR (:hasReasons = true AND r.reason IN :reasons)
                OR r.reporterId IN (
                     SELECT u.id FROM UserEntity u
                     WHERE lower(concat(coalesce(u.firstName, ''), ' ', coalesce(u.lastName, ''))) LIKE :q)
              )
            ORDER BY r.createdAt DESC
            """)
    Page<ReportEntity> searchFiltered(
            @Param("status") ReportStatus status,
            @Param("targetType") ReportTargetType targetType,
            @Param("q") String q,
            @Param("hasReasons") boolean hasReasons,
            @Param("reasons") List<ReportReason> reasons,
            Pageable pageable
    );

    /** Identifiants de TOUS les signalements du filtre courant (« sélectionner tous les résultats »). */
    @Query("""
            SELECT r.id FROM ReportEntity r
            WHERE (:status IS NULL OR r.status = :status)
              AND (:targetType IS NULL OR r.targetType = :targetType)
              AND (:q IS NULL
                OR lower(coalesce(r.description, '')) LIKE :q
                OR lower(coalesce(r.screenRoute, '')) LIKE :q
                OR (:hasReasons = true AND r.reason IN :reasons)
                OR r.reporterId IN (
                     SELECT u.id FROM UserEntity u
                     WHERE lower(concat(coalesce(u.firstName, ''), ' ', coalesce(u.lastName, ''))) LIKE :q)
              )
            """)
    List<UUID> findFilteredIds(
            @Param("status") ReportStatus status,
            @Param("targetType") ReportTargetType targetType,
            @Param("q") String q,
            @Param("hasReasons") boolean hasReasons,
            @Param("reasons") List<ReportReason> reasons
    );

    /** Signalements visant ce compte, pour un statut donné. */
    List<ReportEntity> findByStatusAndTargetTypeAndTargetId(
            ReportStatus status, ReportTargetType targetType, UUID targetId);

    /** Signalements écrits par ce compte, pour un statut donné. */
    List<ReportEntity> findByStatusAndReporterId(ReportStatus status, UUID reporterId);
}
