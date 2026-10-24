package com.yadony.api.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AdminAlertRepository extends JpaRepository<AdminAlertEntity, UUID> {

    List<AdminAlertEntity> findByTypeAndResolved(String type, boolean resolved);

    /**
     * Alertes non résolues qui citent un identifiant : dans leur {@code payload} (même correspondance
     * textuelle que {@code EscrowScheduler}) ou n'importe où dans leur {@code type}
     * (le {@code LIKE} est de forme {@code %<id>%} : {@code ESCROW_CAPTURE_FAILED_<id>}…), les plus récentes d'abord, {@code limit} au plus.
     * {@code payload} est un {@code jsonb} en PostgreSQL : le {@code CAST} en texte vaut aussi sur H2.
     * {@code pattern} est construit par l'appelant à partir d'un UUID, jamais d'une saisie.
     */
    @Query(value = """
            SELECT * FROM admin_alerts a
            WHERE a.resolved = false
              AND (CAST(a.payload AS VARCHAR) LIKE :pattern OR a.type LIKE :pattern)
            ORDER BY a.created_at DESC, a.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<AdminAlertEntity> findOpenReferencing(@Param("pattern") String pattern, @Param("limit") int limit);

    @Query("""
            SELECT a FROM AdminAlertEntity a
            WHERE (:type IS NULL OR a.type = :type)
              AND (:severity IS NULL OR a.severity = :severity)
              AND (:resolved IS NULL OR a.resolved = :resolved)
            """)
    Page<AdminAlertEntity> findFiltered(
            @Param("type") String type,
            @Param("severity") String severity,
            @Param("resolved") Boolean resolved,
            Pageable pageable
    );
}
