package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidNegotiationMessageRepository extends JpaRepository<BidNegotiationMessageEntity, UUID> {

    List<BidNegotiationMessageEntity> findByBidIdOrderByCreatedAtAsc(UUID bidId);

    Optional<BidNegotiationMessageEntity> findFirstByBidIdOrderByCreatedAtDesc(UUID bidId);
}
