package com.yadony.api.requests.event;

import java.util.UUID;

/**
 * Traveler changed the trip linked to a negotiation thread (before payment).
 * Distinct from {@link NegotiationAwaitingTripEvent}, which notifies the
 * traveler that they still need to link a trip — here a trip is already
 * linked and the sender is the one who needs to know it changed.
 */
public record NegotiationTripChangedEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID senderId,
    UUID travelerId,
    UUID travelerAnnouncementId
) {}
