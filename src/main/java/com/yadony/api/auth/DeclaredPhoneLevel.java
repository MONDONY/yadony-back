package com.yadony.api.auth;

/** Niveau de confiance d'un numéro déclaré sans SMS. */
public enum DeclaredPhoneLevel {
    /** Saisi par l'utilisateur, non vérifié. */
    DECLARED,
    /** Confirmé par un administrateur Yadony. */
    YADONY_VERIFIED
}
