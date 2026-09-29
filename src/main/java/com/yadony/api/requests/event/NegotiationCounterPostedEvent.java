package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.util.UUID;

public record NegotiationCounterPostedEvent(
    UUID threadId, UUID messageId,
    UUID fromUserId, UUID toUserId,
    BigDecimal newPriceEur, int roundsCount,
    /** Devise du fil : {@code newPriceEur} est exprimé dans cette devise, pas en euros. */
    String currency,
    /** Montant tel que le destinataire le voit : brut s'il est l'expéditeur, net s'il est le voyageur. */
    BigDecimal amountForRecipient
) {
    /** Sans devise : euros, net affiché tel quel (anciens appelants de test). */
    public NegotiationCounterPostedEvent(UUID threadId, UUID messageId, UUID fromUserId,
                                         UUID toUserId, BigDecimal newPriceEur, int roundsCount) {
        this(threadId, messageId, fromUserId, toUserId, newPriceEur, roundsCount, null, null);
    }
}
