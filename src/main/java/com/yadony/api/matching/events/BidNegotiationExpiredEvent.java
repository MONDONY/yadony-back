package com.yadony.api.matching.events;

import java.util.UUID;

/**
 * Un fil de négociation de trajet s'est éteint tout seul : inactivité prolongée ou
 * trajet déjà parti. Les deux parties sont prévenues via {@code notifications/}.
 *
 * @param reason motif technique ({@code INACTIVE} ou {@code TRIP_DEPARTED}), sans PII
 */
public record BidNegotiationExpiredEvent(
        UUID bidId,
        UUID announcementId,
        UUID senderId,
        UUID travelerId,
        String reason
) {}
