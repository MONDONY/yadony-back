package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Sender accepted the price. The traveler must now link (or create) a trip
 * to provide the sender with concrete trip details before payment.
 */
public record NegotiationAwaitingTripEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID senderId,
    UUID travelerId,
    BigDecimal agreedPriceEur,
    /** Devise du fil : {@code agreedPriceEur} (net du voyageur) est exprimé dans cette devise. */
    String currency
) {
    /** Sans devise : euros (anciens appelants de test). */
    public NegotiationAwaitingTripEvent(UUID threadId, UUID packageRequestId, UUID senderId,
                                        UUID travelerId, BigDecimal agreedPriceEur) {
        this(threadId, packageRequestId, senderId, travelerId, agreedPriceEur, null);
    }
}
