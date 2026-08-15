package com.yadony.api.matching.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Demande CASH créée en attente, destinée à la notification uniquement. */
public record CashBidCreatedEvent(
        UUID bidId,
        UUID announcementId,
        UUID travelerId,
        UUID senderId,
        String senderFirstName,
        BigDecimal weightKg,
        String corridor) {
}
