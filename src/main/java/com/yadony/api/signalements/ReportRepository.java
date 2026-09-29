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

    /** Signalements (non supprimés) visant ces cibles, comptés par cible : lignes {@code [targetId, count]}. */
    @Query("""
            SELECT r.targetId, COUNT(r) FROM ReportEntity r
            WHERE r.targetType = :targetType AND r.targetId IN :targetIds
            GROUP BY r.targetId
            """)
    List<Object[]> countByTargetIds(
            @Param("targetType") ReportTargetType targetType,
            @Param("targetIds") java.util.Collection<UUID> targetIds);

    /** Signalements (non supprimés) d'une cible, du plus récent au plus ancien. */
    List<ReportEntity> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(ReportTargetType targetType, UUID targetId);

    /** Signalements visant ce compte, pour un statut donné. */
    List<ReportEntity> findByStatusAndTargetTypeAndTargetId(
            ReportStatus status, ReportTargetType targetType, UUID targetId);

    /** Signalements écrits par ce compte, pour un statut donné. */
    List<ReportEntity> findByStatusAndReporterId(ReportStatus status, UUID reporterId);

    /**
     * Signalements supprimés (soft delete) pour la corbeille admin, du plus récemment supprimé
     * au plus ancien. Requête native délibérée : le {@code @Where(deleted_at IS NULL)} de
     * {@link ReportEntity} masquerait toutes ces lignes à une requête JPQL. {@code q} (déjà en
     * minuscules, entouré de %) balaie la description, la route d'écran et le code du motif.
     * Le tri est porté par la requête : passer un {@code Pageable} non trié.
     */
    @Query(value = """
            SELECT r.* FROM reports r
            WHERE r.deleted_at IS NOT NULL
              AND (CAST(:status AS VARCHAR) IS NULL OR r.status = CAST(:status AS VARCHAR))
              AND (CAST(:targetType AS VARCHAR) IS NULL OR r.target_type = CAST(:targetType AS VARCHAR))
              AND (CAST(:q AS VARCHAR) IS NULL
                   OR lower(coalesce(r.description, '')) LIKE CAST(:q AS VARCHAR)
                   OR lower(coalesce(r.screen_route, '')) LIKE CAST(:q AS VARCHAR)
                   OR lower(r.reason) LIKE CAST(:q AS VARCHAR))
            ORDER BY r.deleted_at DESC, r.id
            """,
            countQuery = """
            SELECT COUNT(*) FROM reports r
            WHERE r.deleted_at IS NOT NULL
              AND (CAST(:status AS VARCHAR) IS NULL OR r.status = CAST(:status AS VARCHAR))
              AND (CAST(:targetType AS VARCHAR) IS NULL OR r.target_type = CAST(:targetType AS VARCHAR))
              AND (CAST(:q AS VARCHAR) IS NULL
                   OR lower(coalesce(r.description, '')) LIKE CAST(:q AS VARCHAR)
                   OR lower(coalesce(r.screen_route, '')) LIKE CAST(:q AS VARCHAR)
                   OR lower(r.reason) LIKE CAST(:q AS VARCHAR))
            """,
            nativeQuery = true)
    Page<ReportEntity> findDeletedFiltered(
            @Param("status") String status,
            @Param("targetType") String targetType,
            @Param("q") String q,
            Pageable pageable);

    /** Signalements par identifiants, supprimés ou non (restauration unitaire et groupée). */
    @Query(value = "SELECT * FROM reports WHERE id IN (:ids)", nativeQuery = true)
    List<ReportEntity> findAllByIdIncludingDeleted(@Param("ids") java.util.Collection<UUID> ids);

    /**
     * Signalements liés à ces tickets support (lien inverse de la page Support). Un
     * signalement supprimé (soft delete) n'y figure pas : le ticket s'affiche alors sans
     * « Issu du signalement ».
     */
    List<ReportEntity> findBySupportTicketIdIn(java.util.Collection<UUID> supportTicketIds);
}
