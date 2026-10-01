package com.yadony.api.addressbook.invitation;

/** Cycle de vie d'une invitation à rejoindre le carnet d'un expéditeur. */
public enum InvitationStatus {
    /** Envoyée, sans réponse (ou sans compte correspondant). */
    PENDING,
    /** L'invité accepte : ses colis lui sont rattachés directement. */
    ACCEPTED,
    /** L'invité refuse. Jamais révélé à l'inviteur, qui voit toujours PENDING. */
    DECLINED,
    /** Retirée par l'une ou l'autre partie. */
    REVOKED
}
