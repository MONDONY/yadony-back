package com.yadony.api.alerts;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripSpaceAlertRepository extends JpaRepository<TripSpaceAlertEntity, UUID> {

    /** Abonnement en attente (ni notifié, ni retiré) d'une personne sur un trajet. */
    Optional<TripSpaceAlertEntity> findFirstByAnnouncementIdAndSubscriberIdAndNotifiedAtIsNull(
            UUID announcementId, UUID subscriberId);

    /** Abonnés en attente d'un trajet. */
    List<TripSpaceAlertEntity> findAllByAnnouncementIdAndNotifiedAtIsNull(UUID announcementId);

    /** Abonnements en attente d'une personne, du plus récent au plus ancien. */
    List<TripSpaceAlertEntity> findAllBySubscriberIdAndNotifiedAtIsNullOrderByCreatedAtDesc(UUID subscriberId);

    /**
     * Consomme un abonnement s'il est encore en attente. Renvoie 1 au seul appelant qui l'a
     * consommé : deux libérations de place concurrentes ne notifient donc qu'une fois.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE TripSpaceAlertEntity a
        SET a.notifiedAt = :now, a.updatedAt = :now
        WHERE a.id = :id AND a.notifiedAt IS NULL AND a.deletedAt IS NULL
    """)
    int consume(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
