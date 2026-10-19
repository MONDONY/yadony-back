package com.yadony.api.payments.supplement.events;

import com.yadony.api.payments.supplement.BidSupplementRail;
import com.yadony.api.payments.supplement.BidSupplementStatus;

import java.util.UUID;

/**
 * L'argent d'un supplément payé doit être rendu à l'expéditeur : colis terminé sans livraison, ou
 * paiement arrivé après la clôture de la demande. Le rail du supplément exécute le remboursement
 * hors de la transaction qui publie (appel prestataire). {@code status} : statut au moment de la
 * demande ({@code PAID_OUT} ou {@code PAYOUT_PENDING} : versement déjà parti, alerte admin).
 */
public record BidSupplementRefundRequestedEvent(UUID supplementId, UUID bidId, BidSupplementRail rail,
                                                BidSupplementStatus status, String reason) {
}
