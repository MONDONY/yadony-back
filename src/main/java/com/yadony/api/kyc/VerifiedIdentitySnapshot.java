package com.yadony.api.kyc;

/**
 * Extrait des {@code verified_outputs} d'une session Stripe Identity aboutie : ce que la
 * plateforme a le droit de reutiliser pour preremplir l'onboarding Stripe Connect, et rien
 * de plus (pas de numero de piece, pas de sexe, pas de lieu de naissance).
 *
 * <p>Donnees personnelles : ce snapshot ne se journalise jamais et ne se persiste pas — il
 * vit le temps d'un appel de provisioning, en memoire.
 */
public record VerifiedIdentitySnapshot(
        String givenName,
        String surname,
        Long dobDay,
        Long dobMonth,
        Long dobYear,
        String addressLine1,
        String addressLine2,
        String addressCity,
        String addressPostalCode,
        String addressCountry) {

    public boolean hasDob() {
        return dobDay != null && dobMonth != null && dobYear != null;
    }

    public boolean hasAddress() {
        return addressLine1 != null && !addressLine1.isBlank();
    }
}
