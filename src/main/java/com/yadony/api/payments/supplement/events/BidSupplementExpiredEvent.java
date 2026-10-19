package com.yadony.api.payments.supplement.events;

import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.BidSupplementStatus;

import java.util.UUID;

/** Demande de supplément close : voir {@link BidSupplementClosedEvent}. */
public record BidSupplementExpiredEvent(UUID supplementId, UUID bidId, UUID senderId, UUID travelerId,
                                       BidSupplementRail rail, BidSupplementStatus previousStatus)
        implements BidSupplementClosedEvent {
}
