package com.yadony.api.kyc;

/**
 * Decision prise a la main par un administrateur sur la session courante d'une verification.
 *
 * <p>{@code REJECTED} et {@code REVOKED} priment sur le fournisseur : tant qu'une nouvelle
 * session n'a pas ete ouverte, un webhook tardif ne peut plus les defaire.
 */
public enum KycDecisionKind {
    APPROVED,
    REJECTED,
    REVOKED;

    /** Decision negative : le fournisseur ne peut plus faire evoluer la ligne. */
    public boolean overridesProvider() {
        return this == REJECTED || this == REVOKED;
    }
}
