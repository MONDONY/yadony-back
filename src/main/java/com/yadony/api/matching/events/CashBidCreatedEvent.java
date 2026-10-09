package com.yadony.api.matching.events;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Demande CASH en attente du voyageur, destinée à la notification uniquement.
 *
 * <p>{@code negotiated} distingue les deux origines de ce même état PENDING : une demande
 * cash déposée directement ({@code BidService.createBid}, notification « nouvelle demande »)
 * et un accord de prix conclu dans le fil de négociation d'un trajet
 * ({@code BidNegotiationService.accept}), où le voyageur n'a plus de demande à étudier
 * mais une commission à régler (FLUTTER-H7). {@code commissionDueBy} (UTC) est l'heure à
 * laquelle {@code BidTimeoutScheduler} annulera l'accord faute de règlement ; nulle hors
 * négociation.
 */
public record CashBidCreatedEvent(
        UUID bidId,
        UUID announcementId,
        UUID travelerId,
        UUID senderId,
        String senderFirstName,
        BigDecimal weightKg,
        String corridor,
        boolean negotiated,
        LocalDateTime commissionDueBy) {

    /** Demande cash déposée directement, hors négociation. */
    public CashBidCreatedEvent(UUID bidId, UUID announcementId, UUID travelerId, UUID senderId,
                               String senderFirstName, BigDecimal weightKg, String corridor) {
        this(bidId, announcementId, travelerId, senderId, senderFirstName, weightKg, corridor, false, null);
    }
}
