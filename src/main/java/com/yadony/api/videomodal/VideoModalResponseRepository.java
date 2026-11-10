package com.yadony.api.videomodal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VideoModalResponseRepository extends JpaRepository<VideoModalResponseEntity, UUID> {

    Optional<VideoModalResponseEntity> findByCampaignIdAndUserIdAndVersion(UUID campaignId, UUID userId, int version);

    List<VideoModalResponseEntity> findByUserIdAndCampaignIdIn(UUID userId, Collection<UUID> campaignIds);

    /**
     * Statistiques de la version COURANTE de chaque campagne, en utilisateurs distincts (une
     * ligne par utilisateur et par version, garantie par la contrainte d'unicité). Colonnes :
     * campaignId, shown, opened (OPENED ou COMPLETED), completed, dismissed, dontShowAgain.
     */
    @Query("""
            SELECT r.campaignId,
                   COUNT(r),
                   SUM(CASE WHEN r.status IN (com.yadony.api.videomodal.VideoModalEvent.OPENED,
                                              com.yadony.api.videomodal.VideoModalEvent.COMPLETED) THEN 1 ELSE 0 END),
                   SUM(CASE WHEN r.status = com.yadony.api.videomodal.VideoModalEvent.COMPLETED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN r.status = com.yadony.api.videomodal.VideoModalEvent.DISMISSED THEN 1 ELSE 0 END),
                   SUM(CASE WHEN r.dontShowAgainAt IS NOT NULL THEN 1 ELSE 0 END)
            FROM VideoModalResponseEntity r, VideoModalCampaignEntity c
            WHERE c.id = r.campaignId AND r.version = c.version AND c.id IN :campaignIds
            GROUP BY r.campaignId
            """)
    List<Object[]> currentVersionStats(@Param("campaignIds") Collection<UUID> campaignIds);

    /**
     * Nombre de modals (campagnes, toutes versions) montrés à la personne depuis {@code since} :
     * le dernier affichage de chaque ligne suffit à dire « déjà un modal aujourd'hui ».
     */
    @Query("SELECT COUNT(r) FROM VideoModalResponseEntity r WHERE r.userId = :userId AND r.lastShownAt >= :since")
    long countShownSince(@Param("userId") UUID userId, @Param("since") java.time.Instant since);

    /** Finalisation du compte : les réponses de l'utilisateur sont supprimées (douce). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE VideoModalResponseEntity r SET r.deletedAt = :now WHERE r.userId = :userId AND r.deletedAt IS NULL")
    int softDeleteByUserId(@Param("userId") UUID userId, @Param("now") LocalDateTime now);
}
