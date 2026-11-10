package com.yadony.api.dac7;

/**
 * L'identité exigée par la déclaration est-elle disponible ? Jamais un voyageur incomplet n'est déclaré en silence :
 * il sort dans une liste à revue humaine.
 */
public enum Dac7IdentityStatus {
    /** Particulier : nom, prénom, date de naissance et adresse ; professionnel : raison sociale et immatriculation. */
    COMPLETE,
    /** Compte supprimé (identité effacée) ou donnée d'identité manquante. */
    INCOMPLETE
}
