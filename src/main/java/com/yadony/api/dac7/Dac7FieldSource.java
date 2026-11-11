package com.yadony.api.dac7;

/** D'où vient une valeur d'identité montrée au voyageur sur l'écran fiscal. */
public enum Dac7FieldSource {
    /** Pièce d'identité vérifiée par Didit : lecture seule (l'adresse peut être remplacée par une saisie). */
    DIDIT,
    /** Profil du voyageur (saisie) : modifiable. */
    PROFILE
}
