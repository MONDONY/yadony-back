package com.yadony.api.tracking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripPositionSampleRepository extends JpaRepository<TripPositionSampleEntity, UUID> {

    List<TripPositionSampleEntity> findByAnnouncementIdAndCapturedAtBetweenOrderByCapturedAtAsc(
            UUID announcementId, OffsetDateTime from, OffsetDateTime to);

    Optional<TripPositionSampleEntity> findFirstByAnnouncementIdOrderByCapturedAtDesc(UUID announcementId);
}
