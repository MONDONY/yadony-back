package com.yadony.api.disputes.events;

import java.util.UUID;

/** Décision finale prise par l'administration sur un litige. */
public record DisputeResolvedEvent(
        UUID disputeId,
        UUID bidId,
        UUID senderId,
        UUID travelerId,
        String resolution) {
}
