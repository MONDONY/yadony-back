package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Bid carte resté {@code AWAITING_PAYMENT} au-delà de sa fenêtre de paiement : le
 * PaymentIntent est annulé (ou déjà annulé/échoué) et le bid soft-deleted par
 * {@code AwaitingPaymentCleanupScheduler}. Aucun argent n'a été encaissé.
 *
 * <p>Écouté par {@code promo/PromoReleaseListener} : le code promo racheté à la création du
 * PaymentIntent est rendu à l'expéditeur.
 */
public record BidAwaitingPaymentAbandonedEvent(UUID bidId, UUID senderId) {
}
