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
        Instant graceExpiresAt,
        /**
         * Vrai si une session Checkout ouverte maintenant porterait un essai gratuit.
         * Calculé par {@code ProTrialPolicy}, jamais rejoué par le client : le portail
         * n'a qu'une condition à lire, et ne peut pas annoncer un essai que Stripe
         * refuserait.
         */
        boolean trialEligible,
        /** Durée de cet essai en jours, ou {@code null} quand {@code trialEligible} est faux. */
        Integer trialDays
) {
    /**
     * Contrairement à {@link AdminProSubscriptionView#from}, {@code sub == null} ne
     * rend pas {@code null} : l'absence d'abonnement est un état représentable côté
     * client (statut {@code NONE}, inactif), pas une absence de réponse.
     */
    public static ProSubscriptionResponse from(ProSubscriptionEntity sub, Long trialDays) {
        Integer trial = trialDays == null ? null : trialDays.intValue();
        if (sub == null) {
            return new ProSubscriptionResponse(false, "NONE", null, null, null, false, null,
                    trial != null, trial);
        }
        return new ProSubscriptionResponse(
                sub.getStatus().grantsProAccess(),
                sub.getStatus().name(),
                sub.getSource().name(),
                sub.getBillingCycle() == null ? null : sub.getBillingCycle().name(),
                sub.getCurrentPeriodEnd(),
                sub.isCancelAtPeriodEnd(),
                sub.getGraceExpiresAt(),
                trial != null,
                trial
        );
    }
}
