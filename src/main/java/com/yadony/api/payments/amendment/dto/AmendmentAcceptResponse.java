package com.yadony.api.payments.amendment.dto;

import com.yadony.api.matching.dto.BidAmendmentResponse;

/**
 * Réponse à l'acceptation, vue voyageur. {@code commissionClientSecret} : rail espèces, commission payée par
 * carte qui demande une authentification (3DS) ; nul sinon.
 */
public record AmendmentAcceptResponse(BidAmendmentResponse amendment, String commissionClientSecret) {
}
