package com.yadony.api.billing;

/**
 * États d'un abonnement PRO.
 *
 * <p>CANCELED et EXPIRED ferment tous deux l'accès et sont traités
 * identiquement par le gating. Ils restent distincts pour l'analytique :
 * CANCELED = résiliation d'un abonnement payant (churn),
 * EXPIRED = droit non converti arrivé à échéance (grâce ou dunning).
 */
public enum ProSubscriptionStatus {

    ACTIVE,
    PAST_DUE,
    LEGACY_GRACE,
    CANCELED,
    EXPIRED;

    /** Vrai si ce statut doit se traduire par {@code UserEntity.isProAccount == true}. */
    public boolean grantsProAccess() {
        return this == ACTIVE || this == PAST_DUE || this == LEGACY_GRACE;
    }

    /**
     * Vrai si un abonnement dans cet état laisse ouvrir une nouvelle session Checkout.
     * Volontairement distinct de {@link #grantsProAccess()} : {@code LEGACY_GRACE}
     * accorde déjà l'accès mais doit pouvoir souscrire (c'est le but de la grâce
     * historique), alors que {@code ACTIVE} et {@code PAST_DUE} ont déjà un abonnement
     * Stripe en cours et doivent le bloquer.
     */
    public boolean allowsNewCheckout() {
        return this != ACTIVE && this != PAST_DUE;
    }
}
