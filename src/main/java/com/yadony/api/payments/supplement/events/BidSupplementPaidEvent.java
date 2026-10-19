package com.yadony.api.payments.supplement.events;

import com.yadony.api.payments.supplement.BidSupplementRail;

import java.math.BigDecimal;
import java.util.UUID;

/** Supplément payé (carte, mobile money) ou accepté en espèces : le voyageur peut scanner. Publié par les rails. */
public record BidSupplementPaidEvent(UUID supplementId, UUID bidId, UUID senderId, UUID travelerId,
                                     BidSupplementRail rail, BigDecimal grossAmount, String currency) {
}
