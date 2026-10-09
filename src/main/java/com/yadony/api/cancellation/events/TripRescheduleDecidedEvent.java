package com.yadony.api.cancellation.events;

import com.yadony.api.cancellation.RescheduleDecision;

import java.util.UUID;

/**
 * L'expéditeur a répondu au report du trajet : le voyageur en est prévenu.
 *
 * <p>{@code senderFirstName} nourrit le libellé « {prénom} garde son colis » (FLUTTER-EK) ;
 * nul, la notification retombe sur un expéditeur générique.
 *
 * <p>{@code parcelReturnRequired} : l'expéditeur s'est retiré alors que le voyageur avait
 * déjà le colis. Une procédure de retour est ouverte et le voyageur est prévenu par
 * {@link ParcelReturnToSenderRequestedEvent} (« rendez le colis avant le … ») : le simple
 * « colis retiré » n'est alors pas envoyé, pour ne pas doubler la notification.
 */
public record TripRescheduleDecidedEvent(
        UUID bidId,
        UUID senderId,
        UUID travelerId,
        RescheduleDecision decision,
        String senderFirstName,
        boolean parcelReturnRequired
) {
    /** Sans procédure de retour (colis pas encore remis, ou colis gardé). */
    public TripRescheduleDecidedEvent(UUID bidId, UUID senderId, UUID travelerId,
                                      RescheduleDecision decision, String senderFirstName) {
        this(bidId, senderId, travelerId, decision, senderFirstName, false);
    }

    /** Arité historique, sans prénom. */
    public TripRescheduleDecidedEvent(UUID bidId, UUID senderId, UUID travelerId,
                                      RescheduleDecision decision) {
        this(bidId, senderId, travelerId, decision, null, false);
    }
}
