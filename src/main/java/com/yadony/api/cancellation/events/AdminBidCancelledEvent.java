package com.yadony.api.cancellation.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Colis annulé par l'administration ({@code POST /admin/bids/{id}/cancel}). Le remboursement
 * passe par {@code BidRejectedEvent} (motif {@code CANCELLED_BY_ADMIN}) ; cet événement porte
 * ce que seules les notifications et la conversation utilisent : les deux parties à prévenir.
 *
 * @param refundAmount       montant rendu à l'expéditeur (0 si rien n'avait été encaissé)
 * @param parcelWithTraveler colis déjà remis au voyageur : un retour est à organiser
 */
public record AdminBidCancelledEvent(
        UUID bidId,
        UUID announcementId,
        UUID senderId,
        UUID travelerId,
        UUID adminId,
        boolean refundRequested,
        BigDecimal refundAmount,
        String currency,
        boolean parcelWithTraveler
) {}
