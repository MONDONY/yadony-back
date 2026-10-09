package com.yadony.api.matching;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BidNegotiationMessageRepository extends JpaRepository<BidNegotiationMessageEntity, UUID> {

    List<BidNegotiationMessageEntity> findByBidIdOrderByCreatedAtAsc(UUID bidId);

    Optional<BidNegotiationMessageEntity> findFirstByBidIdOrderByCreatedAtDesc(UUID bidId);

    /**
     * Dernier message de chaque fil, en une requête pour toute la liste « Discussions
     * de prix ». Deux messages d'un même fil à la même date exacte remontent tous les
     * deux : l'appelant en garde un par fil.
     */
    @Query("""
        SELECT m FROM BidNegotiationMessageEntity m
        WHERE m.bidId IN :bidIds
          AND m.createdAt = (SELECT MAX(m2.createdAt) FROM BidNegotiationMessageEntity m2
                             WHERE m2.bidId = m.bidId)
    """)
    List<BidNegotiationMessageEntity> findLatestByBidIdIn(@Param("bidIds") Collection<UUID> bidIds);
}
