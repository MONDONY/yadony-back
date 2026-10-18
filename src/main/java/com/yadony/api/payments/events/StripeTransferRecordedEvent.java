package com.yadony.api.payments.events;

import java.util.UUID;

/**
 * Le Transfer Stripe du versement d'un paiement carte vient d'être enregistré (finalisation de
 * {@code StripeReleaseExecutor}). Sert à ouvrir le suivi du virement bancaire du voyageur.
 */
public record StripeTransferRecordedEvent(UUID paymentId, UUID bidId, UUID travelerId, String transferId,
                                          String connectedAccountId) {
}
