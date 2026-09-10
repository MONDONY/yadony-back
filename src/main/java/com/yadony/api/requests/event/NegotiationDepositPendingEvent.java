package com.yadony.api.requests.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/** Dépôt mobile money lancé sur un fil : « Payez votre envoi » à l'expéditeur, information au voyageur. */
public record NegotiationDepositPendingEvent(UUID threadId, UUID packageRequestId, UUID senderId, UUID travelerId,
                                             BigDecimal gross, String currency, LocalDateTime expiresAt) {}
