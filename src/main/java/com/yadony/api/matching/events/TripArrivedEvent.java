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

    /**
     * @param recipientName     nom saisi par l'expéditeur pour le destinataire (peut être null), pour le push
     * @param recipientIsSender l'expéditeur retire lui-même le colis (voir RecipientIsSender)
     */
    public record BidTarget(UUID bidId, UUID senderId, String recipientName, boolean recipientIsSender) {
        /** Sans destinataire connu : texte « votre destinataire ». */
        public BidTarget(UUID bidId, UUID senderId) {
            this(bidId, senderId, null, false);
        }
    }
}
