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
        Integer dunningGraceDays
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
}
