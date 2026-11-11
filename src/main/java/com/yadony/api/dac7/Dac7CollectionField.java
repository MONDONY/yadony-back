package com.yadony.api.dac7;

/**
 * Champ que la collecte DAC7 peut demander (spec § 2.3). Jamais une valeur : seulement un code, sûr dans un journal,
 * un audit ou le registre.
 */
public enum Dac7CollectionField {
    /** Pays de l'adresse principale, seul champ demandé quand il est inconnu (réglé dans le profil). */
    RESIDENCE_COUNTRY,
    /** Prénom et nom. */
    NAME,
    BIRTH_DATE,
    /** Adresse principale complète : rue, code postal, ville (le pays est celui de résidence). */
    ADDRESS,
    /** Numéro d'identification fiscale et pays d'émission (NIF français : 13 chiffres). */
    TAX_IDENTIFICATION_NUMBER
}
