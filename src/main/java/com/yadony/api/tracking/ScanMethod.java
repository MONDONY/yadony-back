package com.yadony.api.tracking;

/**
 * Comment le voyageur a identifié le colis pour valider une étape de suivi.
 * Absent (null) sur les étapes antérieures à V269 et pour les apps qui n'envoient pas le champ.
 */
public enum ScanMethod {
    /** Scan du QR code du colis. */
    QR,
    /** Saisie manuelle du numéro de suivi. */
    MANUAL
}
