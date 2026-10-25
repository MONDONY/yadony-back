package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AnnouncementPriceGridItemRepository extends JpaRepository<AnnouncementPriceGridItemEntity, UUID> {
    List<AnnouncementPriceGridItemEntity> findByAnnouncementIdOrderByPositionAsc(UUID announcementId);

    boolean existsByAnnouncementId(UUID announcementId);

    /** Parmi {@code announcementIds}, les trajets qui ont une grille de prix : une requête par lot. */
    @Query("SELECT DISTINCT g.announcementId FROM AnnouncementPriceGridItemEntity g WHERE g.announcementId IN :ids")
    List<UUID> findAnnouncementIdsWithItems(@Param("ids") Collection<UUID> announcementIds);

    void deleteByAnnouncementId(UUID announcementId);
}
