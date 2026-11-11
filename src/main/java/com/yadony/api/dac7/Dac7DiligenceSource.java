package com.yadony.api.dac7;

/**
 * Origine d'une information fournie, inscrite au registre des diligences (mêmes valeurs que le CHECK de V378).
 * Nulle pour les démarches (demande, rappels, décision) et les lignes antérieures à V378.
 */
public enum Dac7DiligenceSource {
    /** Saisie du voyageur dans l'app. */
    USER,
    /** Reprise de la pièce d'identité vérifiée par Didit ; l'identifiant de la session est gardé comme preuve. */
    DIDIT
}
