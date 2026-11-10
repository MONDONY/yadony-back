package com.yadony.api.dac7;

/** Statut DAC7 d'un voyageur, calculé à partir de son pays de résidence fiscale, jamais deviné. */
public enum Dac7ResidencyStatus {
    /** Résident d'un État membre de l'Union (France comprise) ou d'un État partenaire configuré. */
    DECLARABLE,
    /** Pays de résidence connu, hors Union et hors liste des partenaires : non déclaré (BOI-INT-AEA-30-10 § 150). */
    NON_DECLARABLE,
    /** Pays de résidence absent ou invalide : à obtenir avant toute conclusion. */
    RESIDENCE_INCONNUE
}
