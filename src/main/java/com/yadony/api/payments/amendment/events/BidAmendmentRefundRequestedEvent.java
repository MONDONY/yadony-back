package com.yadony.api.payments.amendment.events;

import com.yadony.api.payments.supplement.BidSupplementRail;

import java.util.UUID;

/**
 * L'argent d'une modification acquise doit être rendu à l'expéditeur (colis terminé sans livraison,
 * modification non apportée). Le rail exécute le remboursement hors de la transaction qui publie.
 *
 * @param reason clé de la fin ({@code BID_REJECTED}, {@code NOT_BROUGHT}…).
 */
public record BidAmendmentRefundRequestedEvent(UUID amendmentId, UUID bidId, BidSupplementRail rail, String reason) {
}
