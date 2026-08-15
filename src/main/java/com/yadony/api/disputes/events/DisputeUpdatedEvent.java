package com.yadony.api.disputes.events;

import java.util.UUID;

/** Mise à jour financière ou opérationnelle importante d'un litige. */
public record DisputeUpdatedEvent(
        UUID disputeId,
        UUID bidId,
        UUID senderId,
        UUID travelerId,
        String updateType) {
}
