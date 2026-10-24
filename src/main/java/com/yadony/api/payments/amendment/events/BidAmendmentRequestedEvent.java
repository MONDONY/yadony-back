package com.yadony.api.payments.amendment.events;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Une modification attend la réponse du voyageur ({@code PROPOSED}) : il est prévenu. Le net est pour
 * lui seul ; toute notification à l'expéditeur n'en reprend que le brut.
 *
 * @param expiresAt échéance de réponse (UTC).
 * @param timezone  fuseau du trajet, pour annoncer l'heure limite telle qu'on la lit sur place.
 */
public record BidAmendmentRequestedEvent(UUID amendmentId, UUID bidId, UUID senderId, UUID travelerId,
                                         BigDecimal grossAmount, BigDecimal netAmount, String currency,
                                         LocalDateTime expiresAt, String timezone) {
}
