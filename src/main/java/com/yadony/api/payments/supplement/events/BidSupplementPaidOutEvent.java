package com.yadony.api.payments.supplement.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Net du supplément versé au voyageur (Transfer Stripe ou payout pawaPay), publié par les rails. */
public record BidSupplementPaidOutEvent(UUID supplementId, UUID bidId, UUID travelerId,
                                        BigDecimal netAmount, String currency) {
}
