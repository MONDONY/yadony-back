package com.yadony.api.travelerstatus;

/** Motif de la décision d'éligibilité à la publication d'un trajet. */
public enum PublishingEligibilityReason {
    /** Le voyageur peut publier. */
    OK,
    /** Limite mensuelle atteinte et statut jamais déclaré : la question du statut doit être posée. */
    STATUS_REQUIRED,
    /** Limite mensuelle atteinte par un particulier : plus de trajet ce mois-ci. */
    LIMIT_REACHED,
    /** Limite mensuelle atteinte, demande GP Pro en cours de validation : il faut attendre la décision. */
    GP_PRO_PENDING
}
