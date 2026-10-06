package com.yadony.api.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Contexte d'un paiement pour le back-office : à quoi il sert (colis classique ou négociation),
 * qui paie, qui est payé, sur quel trajet, et les références Stripe utiles.
 *
 * <p>Un paiement de négociation est rattaché au fil ({@code negotiation_thread_id}) et garde
 * {@code payments.bid_id} vide même après la création du colis : {@code bidId} est ici le colis
 * résolu, par {@code bid_id} ou par {@code bids.linked_negotiation_thread_id}.
 *
 * @param kind               {@code BID} (colis classique) ou {@code NEGOTIATION} (fil de négociation)
 * @param bidId              colis résolu ; {@code null} tant qu'une négociation n'a pas abouti
 * @param abandoned          checkout jamais terminé : PENDING depuis plus de 24 h
 * @param netTravelerCents   montant − commission : ce que le voyageur reçoit (hors remboursement)
 * @param stripeDashboardUrl page du PaymentIntent dans le dashboard Stripe (mode test ou live)
 */
public record AdminPaymentInsight(
        String kind,
        UUID bidId,
        UUID negotiationThreadId,
        Party sender,
        Party traveler,
        String departureCity,
        String arrivalCity,
        String bidStatus,
        boolean abandoned,
        long netTravelerCents,
        Instant capturedAt,
        BigDecimal fxExchangeRate,
        String stripeChargeId,
        String stripeDashboardUrl
) {
    /** Une partie au paiement : identifiant et nom lisible ({@code null} si inconnue). */
    public record Party(UUID id, String name) {
    }
}
