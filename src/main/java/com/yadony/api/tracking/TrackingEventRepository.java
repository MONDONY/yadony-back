package com.yadony.api.tracking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrackingEventRepository extends JpaRepository<TrackingEventEntity, UUID> {

    List<TrackingEventEntity> findByBidIdOrderByScannedAtAsc(UUID bidId);

    List<TrackingEventEntity> findByBidIdInOrderByScannedAtDesc(List<UUID> bidIds);

    boolean existsByBidIdAndEventType(UUID bidId, TrackingEventType eventType);
}
