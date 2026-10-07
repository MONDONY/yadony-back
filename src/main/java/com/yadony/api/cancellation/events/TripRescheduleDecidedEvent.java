package com.yadony.api.cancellation.events;

import com.yadony.api.cancellation.RescheduleDecision;

import java.util.UUID;

/**
 * L'expéditeur a répondu au report du trajet : le voyageur en est prévenu.
 *
 * <p>{@code senderFirstName} nourrit le libellé « {prénom} garde son colis » (FLUTTER-EK) ;
 * nul, la notification retombe sur un expéditeur générique.
 */
public record TripRescheduleDecidedEvent(
        UUID bidId,
        UUID senderId,
        UUID travelerId,
        RescheduleDecision decision,
        String senderFirstName
) {
    /** Arité historique, sans prénom. */
    public TripRescheduleDecidedEvent(UUID bidId, UUID senderId, UUID travelerId,
                                      RescheduleDecision decision) {
        this(bidId, senderId, travelerId, decision, null);
    }
}
