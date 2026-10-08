package com.yadony.api.disputes;

import com.yadony.api.common.MessagingMediaRetentionHold;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.UUID;

/** Un litige non résolu sur le bid retient ses photos de messagerie (FLUTTER-B4). */
@Component
public class DisputeMediaRetentionHold implements MessagingMediaRetentionHold {

    private static final String STATUS_RESOLVED = "RESOLVED";

    private final DisputeRepository disputeRepository;

    public DisputeMediaRetentionHold(DisputeRepository disputeRepository) {
        this.disputeRepository = disputeRepository;
    }

    @Override
    public boolean holds(UUID bidId, Collection<UUID> conversationIds) {
        return bidId != null && disputeRepository.existsByBidIdAndStatusNot(bidId, STATUS_RESOLVED);
    }
}
