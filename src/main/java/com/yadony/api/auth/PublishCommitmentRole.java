package com.yadony.api.auth;

/** Rôle dont l'utilisateur accepte les engagements avant sa première publication. */
public enum PublishCommitmentRole {
    /** Publier un trajet. */
    TRAVELER,
    /** Publier une demande d'envoi. */
    SENDER
}
