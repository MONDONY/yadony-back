package com.yadony.api.billing.dto;

import com.yadony.api.billing.ProSubscriptionEntity;

import java.time.Instant;
import java.util.UUID;

/**
 * État d'abonnement PRO exposé à l'administration.
 *
 * <p>Porte l'origine du droit — payant, offert, ou grâce historique — et, pour un
 * accès offert, qui l'a accordé et pourquoi. Contrairement au DTO exposé à
 * l'utilisateur, celui-ci montre les identifiants Stripe : un administrateur en a
 * besoin pour rapprocher une ligne d'un abonnement dans le dashboard.
 */
public record AdminProSubscriptionView(
        String status,
        String source,
        String billingCycle,
        Instant currentPeriodEnd,
        boolean cancelAtPeriodEnd,
        Instant graceExpiresAt,
        String stripeSubscriptionId,
        UUID grantedByAdminId,
        String adminGrantReason
) {
    public static AdminProSubscriptionView from(ProSubscriptionEntity sub) {
        if (sub == null) {
            return null;
        }
        return new AdminProSubscriptionView(
                sub.getStatus().name(),
                sub.getSource().name(),
                sub.getBillingCycle() == null ? null : sub.getBillingCycle().name(),
                sub.getCurrentPeriodEnd(),
                sub.isCancelAtPeriodEnd(),
                sub.getGraceExpiresAt(),
                sub.getStripeSubscriptionId(),
                sub.getGrantedByAdminId(),
                sub.getAdminGrantReason()
        );
    }
}
