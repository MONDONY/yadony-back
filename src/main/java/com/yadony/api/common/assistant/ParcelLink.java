package com.yadony.api.common.assistant;

/**
 * Écran que l'assistant peut proposer en plus de la fiche du colis. L'assistant en fait un
 * chemin et un libellé dans la langue de l'utilisateur.
 */
public enum ParcelLink {
    /** Feuille de régénération du code de retrait (expéditeur). */
    RENEW_PICKUP_CODE,
    /** Mes litiges. */
    DISPUTES,
    /** Écrire à l'équipe. */
    SUPPORT
}
