package com.yadony.api.disputes.dto;

import java.util.UUID;

/**
 * Litige ouvert par l'administration.
 *
 * @param payoutFrozen  vrai si un versement au voyageur est désormais gelé (paiement en séquestre)
 * @param paymentStatus statut du paiement du colis au moment de l'ouverture, {@code null} sans paiement
 */
public record AdminOpenDisputeResponse(
        UUID disputeId,
        UUID bidId,
        String type,
        String status,
        String openedOnBehalfOf,
        boolean payoutFrozen,
        String paymentStatus
) {
}
