package com.yadony.api.billing.dto;

import java.time.Instant;

/**
 * État d'abonnement exposé au portail web.
 *
 * <p>Ne porte aucun identifiant Stripe : le client n'en a pas besoin, et les
 * exposer élargirait la surface sans contrepartie.
 */
public record ProSubscriptionResponse(
        boolean active,
        String status,
        String source,
        String billingCycle,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Instant graceExpiresAt
) {}
