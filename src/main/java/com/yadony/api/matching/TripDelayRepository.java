package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripDelayRepository extends JpaRepository<TripDelayEntity, UUID> {

    Optional<TripDelayEntity> findFirstByAnnouncementIdOrderByCreatedAtDesc(UUID announcementId);

    /**
     * Retards de plusieurs trajets, le plus récent d'abord : les listes de colis et de trajets en tirent
     * le dernier de chaque trajet en une requête ({@link TripDelays#latestByAnnouncement}).
     */
    @Query("SELECT d FROM TripDelayEntity d WHERE d.announcementId IN :ids ORDER BY d.createdAt DESC, d.id DESC")
    List<TripDelayEntity> findByAnnouncementIdInNewestFirst(@Param("ids") Collection<UUID> ids);
}
