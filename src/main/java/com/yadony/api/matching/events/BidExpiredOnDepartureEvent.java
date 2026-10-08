package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Demande non engagée éteinte par le système, avec remboursement de tout paiement associé
 * ({@code payments/BidExpiredOnDepartureEventListener}).
 *
 * <p>Deux motifs : le départ du voyageur ({@link #REASON_TRIP_DEPARTED}, motif historique) et
 * la date limite de dépôt passée ({@code HandoverDeadlineRules.EXPIRY_REASON}, FLUTTER-GA).
 * Le remboursement est le même ; seule la notification diffère, portée pour le second motif
 * par {@link BidHandoverDeadlinePassedEvent}.
 */
public class BidExpiredOnDepartureEvent {

    public static final String REASON_TRIP_DEPARTED = "TRIP_DEPARTED";

    private final UUID bidId;
    private final UUID senderId;
    private final UUID announcementId;
    private final UUID travelerId;
    private final String reason;

    public BidExpiredOnDepartureEvent(UUID bidId, UUID senderId, UUID announcementId, UUID travelerId) {
        this(bidId, senderId, announcementId, travelerId, REASON_TRIP_DEPARTED);
    }

    public BidExpiredOnDepartureEvent(UUID bidId, UUID senderId, UUID announcementId, UUID travelerId,
                                      String reason) {
        this.bidId = bidId;
        this.senderId = senderId;
        this.announcementId = announcementId;
        this.travelerId = travelerId;
        this.reason = reason != null ? reason : REASON_TRIP_DEPARTED;
    }

    public UUID getBidId() { return bidId; }
    public UUID getSenderId() { return senderId; }
    public UUID getAnnouncementId() { return announcementId; }
    public UUID getTravelerId() { return travelerId; }
    public String getReason() { return reason; }

    /** Le voyageur est parti : c'est le texte historique « Demande expirée » qui s'applique. */
    public boolean isTripDeparted() { return REASON_TRIP_DEPARTED.equals(reason); }
}
