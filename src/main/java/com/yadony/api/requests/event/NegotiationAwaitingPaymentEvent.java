package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Traveler linked a trip. The sender must now pay (Stripe escrow) to finalize.
 */
public record NegotiationAwaitingPaymentEvent(
    UUID threadId,
    UUID packageRequestId,
    UUID senderId,
    UUID travelerId,
    BigDecimal agreedPriceEur,
    UUID travelerAnnouncementId,
    /** Devise du fil : les montants sont exprimés dans cette devise, pas en euros. */
    String currency,
    /** Brut que l'expéditeur va payer (net + commission), c'est lui qui reçoit la notification. */
    BigDecimal grossToPay
) {
    /** Sans devise ni brut : euros, net affiché tel quel (anciens appelants de test). */
    public NegotiationAwaitingPaymentEvent(UUID threadId, UUID packageRequestId, UUID senderId,
                                           UUID travelerId, BigDecimal agreedPriceEur,
                                           UUID travelerAnnouncementId) {
        this(threadId, packageRequestId, senderId, travelerId, agreedPriceEur,
                travelerAnnouncementId, null, null);
    }
}
