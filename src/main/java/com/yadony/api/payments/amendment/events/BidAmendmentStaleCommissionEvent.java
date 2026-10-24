package com.yadony.api.payments.amendment.events;

import java.util.UUID;

/**
 * Une PaymentIntent de commission a abouti sans pouvoir accepter la modification : essai précédent déclaré échoué,
 * commission déjà payée par un autre moyen, modification qui n'attend plus de commission. L'argent est prélevé
 * sans contrepartie : il est rendu au voyageur, PaymentIntent par PaymentIntent
 * ({@code BidAmendmentCashRefunds#refundStale}), après le commit et hors transaction.
 */
public record BidAmendmentStaleCommissionEvent(UUID amendmentId, UUID bidId, String paymentIntentId) {
}
