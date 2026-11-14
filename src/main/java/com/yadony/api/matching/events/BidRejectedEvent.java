package com.yadony.api.matching.events;

import com.yadony.api.matching.BidStatus;

import java.util.UUID;

public class BidRejectedEvent {
    /** Motif posé par {@code BidService.cancelBid} quand l'expéditeur annule lui-même sa demande. */
    public static final String REASON_CANCELLED_BY_SENDER = "CANCELLED_BY_SENDER";
    /** Motif posé par {@code cancellation.AdminBidCancellationService} : colis annulé par l'administration. */
    public static final String REASON_CANCELLED_BY_ADMIN = "CANCELLED_BY_ADMIN";

    private final UUID bidId;
    private final UUID senderId;
    private final String reason;
    private final UUID announcementId;
    private final boolean rematchEligible;
    /**
     * Statut du bid juste avant la transition, quand le publicateur le connaît ({@code null}
     * sinon : jamais deviné). Sert à savoir si la demande a un jour été acceptée.
     */
    private final BidStatus previousStatus;

    public BidRejectedEvent(UUID bidId, UUID senderId, String reason) {
        this(bidId, senderId, reason, null, false);
    }

    public BidRejectedEvent(UUID bidId, UUID senderId, String reason,
                             UUID announcementId, boolean rematchEligible) {
        this(bidId, senderId, reason, announcementId, rematchEligible, null);
    }

    public BidRejectedEvent(UUID bidId, UUID senderId, String reason,
                             UUID announcementId, boolean rematchEligible, BidStatus previousStatus) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.reason = reason;
        this.announcementId = announcementId;
        this.rematchEligible = rematchEligible;
        this.previousStatus = previousStatus;
    }

    public BidStatus getPreviousStatus() { return previousStatus; }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public String getReason() { return reason; }
    public UUID getAnnouncementId() { return announcementId; }
    public boolean isRematchEligible() { return rematchEligible; }
}
