package com.yadony.api.payments.amendment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface BidAmendmentItemRepository extends JpaRepository<BidAmendmentItemEntity, UUID> {

    List<BidAmendmentItemEntity> findByAmendmentId(UUID amendmentId);

    /** Articles de plusieurs modifications (listes de colis) : une requête par lot. */
    List<BidAmendmentItemEntity> findByAmendmentIdIn(Collection<UUID> amendmentIds);
}
