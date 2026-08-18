package com.yadony.api.matching;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages du fil de négociation porté par un bid.
 *
 * <p>Clés propres à {@code matching/} plutôt que réutilisation de
 * {@code yadony.requests.*} : les deux flux de négociation doivent pouvoir
 * diverger sans qu'un package en pilote un autre. Les valeurs par défaut sont
 * alignées sur celles de la négociation de demande d'envoi.
 */
@ConfigurationProperties(prefix = "yadony.matching.negotiation")
public record MatchingNegotiationConfig(
        int maxRounds,
        int inactivityHours,
        int awaitingPaymentHours,
        String expireCheckCron
) { }
