package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Colis payé en espèces resté ACCEPTED après la date limite de remise + 1 h de grâce, sans
 * scan de remise et sans signalement d'aucune des deux parties (FLUTTER-QR). Le colis passe
 * en {@code CANCELLED} sans désigner de fautif : personne n'est sanctionné, la commission
 * prélevée au voyageur lui est rendue.
 *
 * <p>Événement dédié, distinct de {@link VoyageurNoShowEvent} (qui sanctionne le voyageur et
 * conserve la commission espèces) et de {@link BidRejectedEvent} (dont les écouteurs
 * annoncent une annulation « par l'expéditeur/le voyageur », génèrent un rematch et une
 * ligne {@code cancellations} BID_REJECTED_AFTER_PAYMENT) : aucun de ces effets n'est juste
 * ici. Écouteurs : remboursement de la commission ({@code payments/cash}), rendu du code
 * promo ({@code promo}), notifications des deux parties et du destinataire, clôture du
 * trajet si plus aucun colis n'est en cours, métrique.
 *
 * @param bidId             le colis annulé
 * @param announcementId    son trajet
 * @param senderId          l'expéditeur
 * @param travelerId        le voyageur du trajet (nul si le trajet est introuvable)
 * @param commissionCharged une commission était prélevée au voyageur (remboursement attendu)
 */
public record HandoverExpiredEvent(
        UUID bidId,
        UUID announcementId,
        UUID senderId,
        UUID travelerId,
        boolean commissionCharged
) {
}
