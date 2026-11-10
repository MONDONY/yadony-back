package com.yadony.api.dac7;

/**
 * Faut-il demander au voyageur ses informations fiscales ? Seule source de vérité pour les rappels (lot 3b) et l'écran
 * de l'app (lot 3d) : ni l'un ni l'autre n'interroge jamais un voyageur pour lequel la valeur est
 * {@link #NE_PAS_SOLLICITER}. La réponse n'est jamais bloquante : aucune publication, réservation, aucun paiement ni
 * retrait n'en dépend.
 */
public enum Dac7SolicitationStatus {
    /** Résidence connue et déclarable, déjà payé au moins une fois, NIF absent. */
    SOLLICITER,
    /** Résidence inconnue, hors périmètre, ou jamais payé : rien à demander. */
    NE_PAS_SOLLICITER,
    /** NIF déjà fourni : rien à redemander. */
    DEJA_FOURNI
}
