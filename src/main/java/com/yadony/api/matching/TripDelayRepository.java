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
     * Dernier retard de chacun des trajets donnés (au plus un par trajet, sauf égalité d'instant que
     * {@link TripDelays#latestByAnnouncement} départage) : les listes de colis et de trajets le lisent en une
     * requête bornée, quel que soit le nombre de retards passés.
     */
    @Query("""
            SELECT d FROM TripDelayEntity d
            WHERE d.announcementId IN :ids
              AND d.createdAt = (SELECT MAX(d2.createdAt) FROM TripDelayEntity d2
                                 WHERE d2.announcementId = d.announcementId AND d2.deletedAt IS NULL)
            ORDER BY d.id DESC
            """)
    List<TripDelayEntity> findLatestByAnnouncementIdIn(@Param("ids") Collection<UUID> ids);

    /** Retards d'un trajet annoncés depuis {@code since}, le plus récent d'abord (dédoublonnage, plafond). */
    List<TripDelayEntity> findByAnnouncementIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            UUID announcementId, java.time.LocalDateTime since);

    /** Tous les retards actifs d'un trajet (soft delete au report). */
    List<TripDelayEntity> findByAnnouncementId(UUID announcementId);
}
