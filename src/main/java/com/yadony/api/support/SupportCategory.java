package com.yadony.api.support;

/**
 * Categories proposees a l'utilisateur a l'ouverture d'un ticket. Stockees en
 * VARCHAR cote base pour qu'ajouter une categorie plus tard ne demande pas de
 * migration, mais validees a l'entree contre cette enumeration.
 */
public enum SupportCategory {
    ACCOUNT,
    KYC,
    PAYMENT,
    TRIP,
    PACKAGE,
    DELIVERY,
    OTHER
}
