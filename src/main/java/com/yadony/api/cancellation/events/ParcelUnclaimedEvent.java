package com.yadony.api.cancellation.events;

import java.util.UUID;

/**
 * Garde échue sans livraison ni contestation en cours : le colis passe « non réclamé »
 * (FLUTTER-E2). Publié une seule fois par colis (claim atomique sur
 * {@code cancellations.unclaimed_at}).
 *
 * <p>Écouté par {@code payments.DeliveryEventListener}, qui libère le net au voyageur par les
 * mêmes mécanismes que la livraison (claim ESCROW → RELEASED, Transfer Connect ou payout
 * pawaPay) : c'est, avec le force-release admin, la seule libération autorisée sans
 * {@code DeliveryConfirmedEvent} (décision produit FLUTTER-E2). Écouté aussi par
 * {@code NotificationDispatcher}.
 */
public record ParcelUnclaimedEvent(UUID bidId, UUID senderId, UUID travelerId, UUID cancellationId) {
}
