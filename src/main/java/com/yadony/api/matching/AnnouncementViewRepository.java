package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AnnouncementViewRepository extends JpaRepository<AnnouncementViewEntity, UUID> {

    boolean existsByAnnouncementIdAndViewerId(UUID announcementId, UUID viewerId);

    long countByAnnouncementId(UUID announcementId);
}
