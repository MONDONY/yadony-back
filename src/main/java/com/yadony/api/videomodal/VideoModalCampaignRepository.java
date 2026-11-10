package com.yadony.api.videomodal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface VideoModalCampaignRepository extends JpaRepository<VideoModalCampaignEntity, UUID> {

    /** Campagnes actives, version publiée la plus récente d'abord (période filtrée par le service). */
    @Query("""
            SELECT c FROM VideoModalCampaignEntity c
            WHERE c.active = TRUE
            ORDER BY c.versionPublishedAt DESC, c.createdAt DESC
            """)
    List<VideoModalCampaignEntity> findActiveOrderByVersionPublishedAtDesc();

    @Query("SELECT c FROM VideoModalCampaignEntity c ORDER BY c.createdAt DESC")
    List<VideoModalCampaignEntity> findAllNewestFirst();

    /** Clés d'image de toutes les campagnes, supprimées comprises (rapprochement R2). */
    @Query(value = "SELECT image_key FROM video_modal_campaigns WHERE image_key IS NOT NULL "
            + "AND (image_key LIKE CONCAT(:prefix, '%') OR image_key LIKE CONCAT('%/', :prefix, '%'))",
            nativeQuery = true)
    List<String> findImageKeysMatchingIncludingDeleted(@Param("prefix") String prefix);
}
