package com.yadony.api.tracking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripPositionSampleRepository extends JpaRepository<TripPositionSampleEntity, UUID> {

    List<TripPositionSampleEntity> findByAnnouncementIdAndCapturedAtBetweenOrderByCapturedAtAsc(
            UUID announcementId, OffsetDateTime from, OffsetDateTime to);

    Optional<TripPositionSampleEntity> findFirstByAnnouncementIdOrderByCapturedAtDesc(UUID announcementId);

    /*
     * EFFACEMENT PHYSIQUE (DELETE réel, pas de soft delete) : une position, même floue, est une
     * donnée de localisation d'une personne ; elle doit disparaître pour de bon une fois le
     * trajet livré. La règle « pas de DELETE physique » vise les entités métier ; le dépôt purge
     * déjà physiquement les photos et les messages. SQL natif : atteint aussi les lignes
     * éventuellement marquées deleted_at, que le @Where de l'entité masquerait.
     */

    /** Efface toutes les positions d'un trajet. Retourne le nombre de lignes effacées. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM trip_position_samples WHERE announcement_id = :announcementId",
            nativeQuery = true)
    int eraseByAnnouncementId(@Param("announcementId") UUID announcementId);

    /** Efface les positions de tous les trajets d'un voyageur (suppression de compte). */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM trip_position_samples WHERE announcement_id IN "
            + "(SELECT id FROM announcements WHERE traveler_id = :travelerId)", nativeQuery = true)
    int eraseByTravelerId(@Param("travelerId") UUID travelerId);

    /**
     * Filet de sécurité, un lot : positions d'un trajet clos (COMPLETED, CANCELLED,
     * REMOVED_BY_ADMIN) ou plus anciennes que {@code cutoff}.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM trip_position_samples WHERE id IN ("
            + "SELECT s.id FROM trip_position_samples s JOIN announcements a ON a.id = s.announcement_id "
            + "WHERE a.status IN ('COMPLETED', 'CANCELLED', 'REMOVED_BY_ADMIN') OR s.captured_at < :cutoff "
            + "LIMIT :batchSize)", nativeQuery = true)
    int eraseClosedOrExpiredBatch(@Param("cutoff") OffsetDateTime cutoff, @Param("batchSize") int batchSize);
}
