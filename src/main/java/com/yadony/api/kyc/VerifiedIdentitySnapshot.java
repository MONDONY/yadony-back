package com.yadony.api.kyc;

/**
 * Extrait des {@code verified_outputs} d'une session Stripe Identity aboutie : le nom verifie
 * sur piece, et rien de plus (pas de date de naissance, pas d'adresse, pas de numero de piece).
 *
 * <p>Le perimetre s'arrete la parce que c'est tout ce que la plateforme prereplit desormais :
 * l'etat civil complet — date de naissance, adresse de residence — est demande par le
 * formulaire Stripe Connect, qui fait autorite sur ces champs et les revalide de toute facon.
 *
 * <p>Donnees personnelles : ce snapshot ne se journalise jamais et ne se persiste pas — il
 * vit le temps d'un appel de provisioning, en memoire.
 */
public record VerifiedIdentitySnapshot(String givenName, String surname) {

    public boolean hasName() {
        return (givenName != null && !givenName.isBlank())
                || (surname != null && !surname.isBlank());
    }
}
