package com.yadony.api.payments.amendment.events;

import java.math.BigDecimal;
import java.util.UUID;

/** Net de la modification versé au voyageur (Transfer Stripe ou payout pawaPay), publié par les rails. */
public record BidAmendmentPaidOutEvent(UUID amendmentId, UUID bidId, UUID travelerId, BigDecimal netAmount,
                                       String currency) {
}
