package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AnnouncementPriceGridItemRepository extends JpaRepository<AnnouncementPriceGridItemEntity, UUID> {
    List<AnnouncementPriceGridItemEntity> findByAnnouncementIdOrderByPositionAsc(UUID announcementId);

    boolean existsByAnnouncementId(UUID announcementId);
    void deleteByAnnouncementId(UUID announcementId);
}
