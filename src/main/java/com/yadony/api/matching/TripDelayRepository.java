package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TripDelayRepository extends JpaRepository<TripDelayEntity, UUID> {

    Optional<TripDelayEntity> findFirstByAnnouncementIdOrderByCreatedAtDesc(UUID announcementId);
}
