package com.yadony.api.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration de l'abonnement PRO.
 *
 * <p>Détecté automatiquement : {@code @ConfigurationPropertiesScan} est actif
 * sur {@code YadonyBackApplication}.
 */
@ConfigurationProperties(prefix = "yadony.billing")
public record BillingProperties(
        Boolean schedulerEnabled,
        Integer legacyGraceDays,
        Integer dunningGraceDays,
        String priceMonthly,
        String priceYearly,
        String successUrl,
        String cancelUrl,
        String portalReturnUrl,
        Integer trialDays
) {

    /**
     * Interrupteur général des tâches planifiées de downgrade.
     *
     * <p>Faux par défaut, volontairement : déployer le lot 1 sans le lot 2
     * lancerait un compte à rebours d'expiration sur des comptes qui n'ont
     * encore aucun moyen de payer. À passer à vrai seulement une fois le
     * parcours de paiement vérifié en production.
     */
    public boolean schedulerEnabledOrDefault() {
        return schedulerEnabled != null && schedulerEnabled;
    }

    public int legacyGraceDaysOrDefault() {
        return legacyGraceDays != null ? legacyGraceDays : 60;
    }

    public int dunningGraceDaysOrDefault() {
        return dunningGraceDays != null ? dunningGraceDays : 5;
    }

    /**
     * Vrai si les identifiants de Price Stripe sont renseignés. Faux en
     * développement et en test, où le dashboard Stripe n'est pas configuré :
     * l'application doit démarrer quand même, et c'est l'appel à
     * {@code POST /billing/checkout-session} qui échoue proprement.
     */
    public boolean stripePricesConfigured() {
        return priceMonthly != null && !priceMonthly.isBlank()
                && priceYearly != null && !priceYearly.isBlank();
    }

    /**
     * Durée de l'essai gratuit, en jours, ou {@code null} s'il n'y en a pas.
     *
     * <p>Stripe refuse un {@code trial_period_days} nul ou négatif : une valeur absente,
     * zéro ou négative signifie donc « pas d'essai », et l'appel omet simplement le champ
     * plutôt que d'envoyer une valeur que Stripe rejetterait.
     */
    public Long trialDaysOrNull() {
        return trialDays != null && trialDays > 0 ? trialDays.longValue() : null;
    }

    public String priceFor(BillingCycle cycle) {
        return cycle == BillingCycle.YEARLY ? priceYearly : priceMonthly;
    }
}
