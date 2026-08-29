package com.yadony.api.billing;

/**
 * Origine du droit PRO.
 *
 * <p>LEGACY_FREE identifie la cohorte des comptes passés PRO gratuitement
 * avant l'introduction de l'abonnement payant : ni payants, ni offerts par
 * un administrateur. Nécessaire pour suivre leur taux de conversion.
 */
public enum ProSubscriptionSource {
    STRIPE,
    ADMIN_GRANT,
    LEGACY_FREE
}
