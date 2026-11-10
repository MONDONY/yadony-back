package com.yadony.api.videomodal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
}
