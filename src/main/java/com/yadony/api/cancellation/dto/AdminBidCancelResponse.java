package com.yadony.api.cancellation.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Résultat d'une annulation admin.
 *
 * @param alreadyCancelled   le colis était déjà annulé : rien n'a été refait (appel idempotent)
 * @param refundRequested    un remboursement (ou l'annulation de l'autorisation carte) est lancé
 * @param refundAmount       montant rendu à l'expéditeur, 0 si rien n'avait été encaissé
 * @param parcelWithTraveler le colis était déjà remis au voyageur : un retour est à organiser
 */
public record AdminBidCancelResponse(
        UUID bidId,
        String status,
        String previousStatus,
        boolean alreadyCancelled,
        boolean refundRequested,
        String paymentStatus,
        BigDecimal refundAmount,
        String currency,
        boolean parcelWithTraveler
) {
}
