package com.yadony.api.matching.events;

import java.util.List;
import java.util.UUID;

/**
 * Published by AnnouncementService#markArrived when the traveler confirms
 * arrival at destination for a trip. Carries one target per active bid so
 * that notifications/ and messaging/ can act per-parcel without re-querying
 * the trip.
 */
public class TripArrivedEvent {

    private final UUID announcementId;
    private final List<BidTarget> targets;

    public TripArrivedEvent(UUID announcementId, List<BidTarget> targets) {
        this.announcementId = announcementId;
        this.targets = targets;
    }

    public UUID getAnnouncementId() { return announcementId; }
    public List<BidTarget> getTargets() { return targets; }

    public record BidTarget(UUID bidId, UUID senderId) {}
}
