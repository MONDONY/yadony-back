package com.yadony.api.matching.dto;

/**
 * Numéro de la contrepartie d'un colis, renvoyé uniquement sur demande explicite
 * (l'utilisateur appuie sur « appeler »).
 *
 * <p>Le numéro ne voyage plus dans les réponses de liste : il ne quitte le serveur
 * qu'au moment où quelqu'un veut réellement téléphoner. Chaque révélation est
 * journalisée dans {@code audit_log}.
 */
public record ContactPhoneResponse(
        /** Numéro E.164 : celui vérifié par SMS s'il existe, sinon le numéro déclaré ; null si aucun. */
        String phoneNumber,
        /** {@code SMS}, {@code YADONY} (déclaré, vérifié par l'équipe) ou {@code NONE} (non vérifié). */
        String verification
) {
    /** Forme d'avant le numéro déclaré : numéro sans précision, jamais présenté comme vérifié. */
    public ContactPhoneResponse(String phoneNumber) {
        this(phoneNumber, "NONE");
    }
}
