package com.yadony.api.cancellation.events;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TripCancelledEvent {
    private final UUID announcementId;
    private final UUID travelerId;
    private final List<UUID> affectedSenderIds;
    private final String reason;
    /** Story 6.7 — bid IDs whose escrow must be refunded. */
    private final List<UUID> affectedBidIds;
    /**
     * Task 7 — paymentMethod per bid so wallet listeners don't need BidRepository.
     * Key = bidId, value = PaymentMethod name ("CASH", "STRIPE", …).
     */
    private final Map<UUID, String> bidPaymentMethods;
    /**
     * Canal de prélèvement de la commission par bid ("WALLET" ou "CARD", null si inconnu).
     * Permet aux listeners de remboursement de router vers wallet.credit() ou Stripe Refund.
     */
    private final Map<UUID, String> bidCommissionChargedVia;
    /** Suggestions rematch par expéditeur (Story 5.6). Clé = senderId. */
    public record RematchBySenderInfo(UUID cancellationId, int suggestionCount) {}
    private final Map<UUID, RematchBySenderInfo> rematchBySender;
    /**
     * Bids dont le colis était déjà entre les mains du voyageur : une procédure de retour
     * (code de retour, délai) leur a été ouverte. L'expéditeur reçoit alors
     * {@code PARCEL_RETURN_REQUIRED} au lieu du simple « trajet annulé » (FLUTTER-FK).
     */
    private final Set<UUID> returnRequiredBidIds;

    /** Full constructor. */
    public TripCancelledEvent(UUID announcementId, UUID travelerId,
                               List<UUID> affectedSenderIds, String reason,
                               List<UUID> affectedBidIds,
                               Map<UUID, String> bidPaymentMethods,
                               Map<UUID, String> bidCommissionChargedVia,
                               Map<UUID, RematchBySenderInfo> rematchBySender,
                               Set<UUID> returnRequiredBidIds) {
        this.announcementId = announcementId;
        this.travelerId = travelerId;
        this.affectedSenderIds = affectedSenderIds;
        this.reason = reason;
        this.affectedBidIds = affectedBidIds;
        this.bidPaymentMethods = bidPaymentMethods != null ? bidPaymentMethods : Map.of();
        this.bidCommissionChargedVia = bidCommissionChargedVia != null ? bidCommissionChargedVia : Map.of();
        this.rematchBySender = rematchBySender != null ? rematchBySender : Map.of();
        this.returnRequiredBidIds = returnRequiredBidIds != null ? returnRequiredBidIds : Set.of();
    }

    /** Backward-compatible constructor — aucun colis à restituer. */
    public TripCancelledEvent(UUID announcementId, UUID travelerId,
                               List<UUID> affectedSenderIds, String reason,
                               List<UUID> affectedBidIds,
                               Map<UUID, String> bidPaymentMethods,
                               Map<UUID, String> bidCommissionChargedVia,
                               Map<UUID, RematchBySenderInfo> rematchBySender) {
        this(announcementId, travelerId, affectedSenderIds, reason, affectedBidIds, bidPaymentMethods,
                bidCommissionChargedVia, rematchBySender, Set.of());
    }

    /** Backward-compatible constructor — rematchBySender defaults to empty map. */
    public TripCancelledEvent(UUID announcementId, UUID travelerId,
                               List<UUID> affectedSenderIds, String reason,
                               List<UUID> affectedBidIds,
                               Map<UUID, String> bidPaymentMethods,
                               Map<UUID, String> bidCommissionChargedVia) {
        this(announcementId, travelerId, affectedSenderIds, reason, affectedBidIds, bidPaymentMethods, bidCommissionChargedVia, Map.of());
    }

    /** Backward-compatible constructor — bidCommissionChargedVia and rematchBySender default to empty map. */
    public TripCancelledEvent(UUID announcementId, UUID travelerId,
                               List<UUID> affectedSenderIds, String reason,
                               List<UUID> affectedBidIds,
                               Map<UUID, String> bidPaymentMethods) {
        this(announcementId, travelerId, affectedSenderIds, reason, affectedBidIds, bidPaymentMethods, Map.of(), Map.of());
    }

    /** Backward-compatible constructor — all maps default to empty. */
    public TripCancelledEvent(UUID announcementId, UUID travelerId,
                               List<UUID> affectedSenderIds, String reason,
                               List<UUID> affectedBidIds) {
        this(announcementId, travelerId, affectedSenderIds, reason, affectedBidIds, Map.of(), Map.of(), Map.of());
    }

    public UUID getAnnouncementId() { return announcementId; }
    public UUID getTravelerId() { return travelerId; }
    public List<UUID> getAffectedSenderIds() { return affectedSenderIds; }
    public String getReason() { return reason; }
    public List<UUID> getAffectedBidIds() { return affectedBidIds; }
    public Map<UUID, String> getBidPaymentMethods() { return bidPaymentMethods; }
    public Map<UUID, String> getBidCommissionChargedVia() { return bidCommissionChargedVia; }
    public Map<UUID, RematchBySenderInfo> getRematchBySender() { return rematchBySender; }
    public Set<UUID> getReturnRequiredBidIds() { return returnRequiredBidIds; }
}
