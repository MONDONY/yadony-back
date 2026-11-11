package com.yadony.api.dac7;

/**
 * L'identité exigée par la déclaration est-elle disponible ? Jamais un voyageur incomplet n'est déclaré en silence :
 * il sort dans une liste à revue humaine.
 */
public enum Dac7IdentityStatus {
    /** Particulier : nom, prénom, date de naissance et adresse ; professionnel : raison sociale et immatriculation. */
    COMPLETE,
    /** Compte supprimé (identité effacée) ou donnée d'identité manquante. */
    INCOMPLETE,
    /**
     * Lot 3b : le prestataire de vérification n'a pas répondu et le profil seul ne suffit pas. Réessayable, jamais
     * confondu avec INCOMPLETE ; bloque l'export complet ({@code IDENTITY_UNAVAILABLE}).
     */
    UNAVAILABLE,
    /**
     * Relecture n° 3, finding 6 : pages admin et CSV de contrôle seulement (verdicts en cache). Le voyageur n'a pas été
     * lu pendant cet appel (au-delà de {@code Dac7IdentityResolver.MAX_LOOKUPS_PER_REQUEST} lectures nouvelles, ou
     * lecture pas terminée dans le budget) : on ne sait pas encore, ce n'est PAS une panne du prestataire. Jamais
     * produit par l'export ni les récapitulatifs, qui relisent chaque pièce en direct.
     */
    NOT_CHECKED
}
