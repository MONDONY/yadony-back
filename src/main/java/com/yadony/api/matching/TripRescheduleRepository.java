package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TripRescheduleRepository extends JpaRepository<TripRescheduleEntity, UUID> {

    Optional<TripRescheduleEntity> findFirstByAnnouncementIdOrderByCreatedAtDesc(UUID announcementId);
}
