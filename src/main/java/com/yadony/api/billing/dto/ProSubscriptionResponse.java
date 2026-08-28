package com.yadony.api.billing.dto;

import com.yadony.api.billing.ProSubscriptionEntity;

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
) {
    /**
     * Contrairement à {@link AdminProSubscriptionView#from}, {@code sub == null} ne
     * rend pas {@code null} : l'absence d'abonnement est un état représentable côté
     * client (statut {@code NONE}, inactif), pas une absence de réponse.
     */
    public static ProSubscriptionResponse from(ProSubscriptionEntity sub) {
        if (sub == null) {
            return new ProSubscriptionResponse(false, "NONE", null, null, null, false, null);
        }
        return new ProSubscriptionResponse(
                sub.getStatus().grantsProAccess(),
                sub.getStatus().name(),
                sub.getSource().name(),
                sub.getBillingCycle() == null ? null : sub.getBillingCycle().name(),
                sub.getCurrentPeriodEnd(),
                sub.isCancelAtPeriodEnd(),
                sub.getGraceExpiresAt()
        );
    }
}
