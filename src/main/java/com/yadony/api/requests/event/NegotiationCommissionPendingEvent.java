package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * L'expéditeur a conclu en espèces : le voyageur doit régler la commission Yadony
 * avant {@code expiresAt} pour emporter la demande. Rien n'est scellé, la demande
 * reste ouverte et un autre voyageur peut la conclure entre-temps.
 */
public record NegotiationCommissionPendingEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID travelerId,
    UUID senderId,
    BigDecimal commissionAmount,
    String currency,
    LocalDateTime expiresAt
) {}
