package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface BidGridItemRepository extends JpaRepository<BidGridItemEntity, UUID> {
    List<BidGridItemEntity> findByBidId(UUID bidId);

    /** Articles de plusieurs bids en une requête (listes paginées du back-office). */
    List<BidGridItemEntity> findByBidIdIn(java.util.Collection<UUID> bidIds);
}
