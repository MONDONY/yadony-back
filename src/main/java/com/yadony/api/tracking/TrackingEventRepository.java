package com.yadony.api.tracking;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrackingEventRepository extends JpaRepository<TrackingEventEntity, UUID> {

    List<TrackingEventEntity> findByBidIdOrderByScannedAtAsc(UUID bidId);

    List<TrackingEventEntity> findByBidIdInOrderByScannedAtDesc(List<UUID> bidIds);

    boolean existsByBidIdAndEventType(UUID bidId, TrackingEventType eventType);

    Optional<TrackingEventEntity> findFirstByBidIdAndEventTypeOrderByScannedAtAsc(UUID bidId,
                                                                              TrackingEventType eventType);

    @Query("SELECT DISTINCT e.bidId FROM TrackingEventEntity e WHERE e.photoUrl IS NOT NULL AND e.photoPurgedAt IS NULL")
    List<UUID> findBidIdsWithLivePhotos();

    @Query("SELECT e FROM TrackingEventEntity e WHERE e.bidId = :bidId AND e.photoUrl IS NOT NULL AND e.photoPurgedAt IS NULL")
    List<TrackingEventEntity> findLivePhotosByBidId(@Param("bidId") UUID bidId);

    /**
     * Rapprochement R2 : photos de scan non purgées dont la valeur désigne une clé du préfixe
     * (clé brute ou URL héritée contenant « /prefix »), filtrées ensuite par MediaKeys.
     */
    @Query("SELECT e.photoUrl FROM TrackingEventEntity e WHERE e.photoPurgedAt IS NULL "
            + "AND (e.photoUrl LIKE CONCAT(:prefix, '%') OR e.photoUrl LIKE CONCAT('%/', :prefix, '%'))")
    List<String> findLivePhotoValuesMatching(@Param("prefix") String prefix);
}
