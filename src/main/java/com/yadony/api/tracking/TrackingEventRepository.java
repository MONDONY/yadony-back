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
}
