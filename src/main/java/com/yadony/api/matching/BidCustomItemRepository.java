package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BidCustomItemRepository extends JpaRepository<BidCustomItemEntity, UUID> {

    List<BidCustomItemEntity> findByBidIdOrderByCreatedAtAsc(UUID bidId);
}
