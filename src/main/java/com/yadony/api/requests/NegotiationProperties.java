package com.yadony.api.requests;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Délai laissé au voyageur pour régler la commission Yadony d'un accord en
 * espèces avant que le thread expire et libère la demande à d'autres voyageurs.
 */
@ConfigurationProperties(prefix = "yadony.negotiation")
public record NegotiationProperties(
    int commissionWindowMinutes
) {}
