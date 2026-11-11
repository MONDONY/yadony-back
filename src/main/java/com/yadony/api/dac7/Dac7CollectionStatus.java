package com.yadony.api.dac7;

/**
 * Statut de COLLECTE DAC7 d'un voyageur (spec du 11/11/2026, § 2.2), au-dessus de {@link Dac7ResidencyStatus} et de
 * {@link Dac7SolicitationStatus} qui restent inchangés (arbitrage R3). Dit à l'app quoi demander, jamais bloquant
 * (arbitrage R1) : aucune acceptation, publication, aucun paiement ni retrait n'en dépend.
 */
public enum Dac7CollectionStatus {
    /** Pays de résidence connu hors du périmètre transmis (hors Union) : rien à demander, jamais d'écran fiscal. */
    NE_PAS_SOLLICITER,
    /** Pays de résidence inconnu : on ne demande QUE le pays. */
    RESIDENCE_INCONNUE,
    /** Résident de l'Union, informations incomplètes : la liste exacte des champs manquants accompagne ce statut. */
    INFO_REQUIRED,
    /** Résident de l'Union, tout est présent : rien à demander. */
    COMPLETE;

    /** Faut-il inviter le voyageur à compléter quelque chose ? */
    public boolean actionRequired() {
        return this == RESIDENCE_INCONNUE || this == INFO_REQUIRED;
    }
}
