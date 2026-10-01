package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface AnnouncementViewRepository extends JpaRepository<AnnouncementViewEntity, UUID> {

    boolean existsByAnnouncementIdAndViewerId(UUID announcementId, UUID viewerId);

    long countByAnnouncementId(UUID announcementId);

    /** Personnes par trajet, en une requête : lignes {@code [announcementId, count]}, sans les trajets jamais vus. */
    @Query("SELECT v.announcementId, COUNT(v) FROM AnnouncementViewEntity v "
           + "WHERE v.announcementId IN :ids GROUP BY v.announcementId")
    List<Object[]> countByAnnouncementIds(@Param("ids") Collection<UUID> ids);
}
