package com.yadony.api.auth;

/** Compte de versement mobile money d'un utilisateur, jumeau de {@link StripeAccountStatus}. */
public enum MobileMoneyPayoutStatus {
    /** Jamais activé. */
    NOT_CONFIGURED,
    /** Activé : le numéro Firebase vérifié est snapshoté, l'opérateur prédit par pawaPay. */
    ACTIVE,
    /** Désactivé par l'utilisateur ; le numéro est conservé pour une réactivation en un geste. */
    DISABLED
}
