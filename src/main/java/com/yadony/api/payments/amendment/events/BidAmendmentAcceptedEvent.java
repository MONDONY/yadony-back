package com.yadony.api.payments.amendment.events;

import com.yadony.api.payments.supplement.BidSupplementRail;

import java.util.UUID;

/**
 * Le voyageur a accepté la modification. {@code needsPayment} : l'expéditeur doit encore payer
 * (mobile money, payé après l'acceptation) ; faux quand la modification est acquise ({@code ACCEPTED}).
 */
public record BidAmendmentAcceptedEvent(UUID amendmentId, UUID bidId, UUID senderId, UUID travelerId,
                                        BidSupplementRail rail, boolean needsPayment) {
}
