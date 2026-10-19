package com.yadony.api.payments.supplement.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Argent du supplément rendu, publié par les rails : à l'expéditeur (brut, carte ou mobile money)
 * ou au voyageur ({@code commissionOnly} : commission espèces du supplément recréditée).
 */
public record BidSupplementRefundedEvent(UUID supplementId, UUID bidId, UUID recipientId,
                                         BigDecimal amount, String currency, boolean commissionOnly) {
}
