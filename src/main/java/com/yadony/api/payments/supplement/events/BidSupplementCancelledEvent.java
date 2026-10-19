package com.yadony.api.payments.supplement.events;

import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.BidSupplementStatus;

import java.util.UUID;

/**
 * Demande annulée parce que le colis s'est terminé sans livraison ({@code reason} : clé du plan
 * §5.1, ex. {@code PARCEL_REFUSED}). Pas de notification propre : la fin du colis est déjà annoncée.
 */
public record BidSupplementCancelledEvent(UUID supplementId, UUID bidId, UUID senderId, UUID travelerId,
                                          BidSupplementRail rail, BidSupplementStatus previousStatus,
                                          String reason)
        implements BidSupplementClosedEvent {
}
