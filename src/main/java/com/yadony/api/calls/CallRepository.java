package com.yadony.api.calls;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CallRepository extends JpaRepository<CallEntity, UUID> {
    Optional<CallEntity> findByStreamCallId(String streamCallId);
    boolean existsByConversationIdAndStatusIn(UUID conversationId, Collection<CallStatus> statuses);
    List<CallEntity> findByBidIdOrderByCreatedAtDesc(UUID bidId);
}
