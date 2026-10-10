package com.yadony.api.cancellation.dto;

import java.util.UUID;

/**
 * Réponse de {@code POST /bids/{bidId}/cancel-before-payment}.
 *
 * @param alreadyCancelled vrai si la demande était déjà annulée (double appel, ou délai de
 *                         paiement écoulé au même moment) : rien n'a été refait
 */
public record PrePaymentCancellationResponse(UUID bidId, String status, boolean alreadyCancelled) {
}
