package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.util.UUID;

public record NegotiationStartedEvent(
    UUID threadId, UUID packageRequestId,
    UUID senderId, UUID travelerId,
    BigDecimal proposedPriceEur,
    /** Devise du fil : {@code proposedPriceEur} est exprimé dans cette devise, pas en euros. */
    String currency,
    /** Brut vu par l'expéditeur (destinataire de la notification) pour ce net proposé. */
    BigDecimal proposedGross
) {
    /** Sans devise ni brut : euros, net affiché tel quel (anciens appelants de test). */
    public NegotiationStartedEvent(UUID threadId, UUID packageRequestId, UUID senderId,
                                   UUID travelerId, BigDecimal proposedPriceEur) {
        this(threadId, packageRequestId, senderId, travelerId, proposedPriceEur, null, null);
    }
}
