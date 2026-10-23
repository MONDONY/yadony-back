package com.yadony.api.payments.amendment.events;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Argent d'une modification rendu, publié par les rails : à l'expéditeur (brut, carte ou mobile money) ou
 * au voyageur ({@code commissionOnly} : commission espèces de la modification recréditée).
 */
public record BidAmendmentRefundedEvent(UUID amendmentId, UUID bidId, UUID recipientId, BigDecimal amount,
                                        String currency, boolean commissionOnly) {
}
