package com.yadony.api.cancellation.events;

import java.util.UUID;

/**
 * L'expéditeur a annulé sa demande avant de payer ({@code AWAITING_PAYMENT → CANCELLED}).
 * Aucun argent n'a été encaissé : l'autorisation carte ou le paiement mobile money en attente a
 * déjà été libéré dans la transaction qui publie cet événement.
 *
 * <p>Écouté après commit par {@code notifications/} (voyageur prévenu s'il connaissait la
 * demande), {@code messaging/} (message système dans la conversation, qui reste en place) et
 * {@code promo/} (code promo rendu).
 *
 * @param travelerAware vrai quand le voyageur connaissait la demande : il l'a acceptée (mobile
 *                      money) ou en a négocié le prix. Une réservation carte directe dont
 *                      l'expéditeur n'a jamais validé le paiement ne lui a jamais été montrée :
 *                      le prévenir de son annulation n'aurait aucun sens.
 * @param releasedKg    kilos rendus à l'annonce (nul si aucune capacité n'était réservée)
 */
public record BidCancelledBeforePaymentEvent(
        UUID bidId,
        UUID senderId,
        UUID travelerId,
        UUID announcementId,
        String paymentMethod,
        boolean travelerAware,
        java.math.BigDecimal releasedKg) {
}
