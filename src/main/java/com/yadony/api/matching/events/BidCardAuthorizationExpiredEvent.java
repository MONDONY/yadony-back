package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * L'autorisation carte d'une demande en attente de réponse du voyageur a expiré chez Stripe
 * (environ 7 jours après le paiement, {@code payment_intent.canceled} avec le motif
 * {@code automatic}) : la demande est éteinte (EXPIRED), la carte n'a jamais été débitée.
 *
 * <p>Événement de notification uniquement, pour l'expéditeur : il doit pouvoir repayer. Le
 * voyageur n'est pas prévenu, la demande ne lui a jamais coûté de capacité.
 *
 * @param bidId          la demande éteinte
 * @param announcementId le trajet visé
 * @param senderId       l'expéditeur, destinataire de la notification
 * @param travelerId     le voyageur du trajet
 */
public record BidCardAuthorizationExpiredEvent(
        UUID bidId,
        UUID announcementId,
        UUID senderId,
        UUID travelerId
) {
    /** Motif tracé dans le bid, l'audit et la notification. */
    public static final String REASON = "CARD_AUTHORIZATION_EXPIRED";
}
