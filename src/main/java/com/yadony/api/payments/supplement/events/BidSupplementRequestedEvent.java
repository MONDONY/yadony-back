package com.yadony.api.payments.supplement.events;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Le voyageur demande un supplément : l'expéditeur est prévenu (brut seulement, jamais le net).
 *
 * @param expiresAt échéance de réponse (UTC).
 * @param timezone  fuseau du trajet, pour annoncer l'heure limite telle qu'on la lit sur place.
 */
public record BidSupplementRequestedEvent(UUID supplementId, UUID bidId, UUID senderId, UUID travelerId,
                                          BigDecimal grossAmount, String currency, LocalDateTime expiresAt,
                                          String timezone) {
}
