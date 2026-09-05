package com.yadony.api.support;

/**
 * Cycle de vie d'un ticket support. Un ticket {@link #RESOLVED} est terminal :
 * un nouveau probleme ouvre un nouveau ticket, on ne rouvre jamais l'ancien.
 */
public enum SupportTicketStatus {
    /** Cree par l'utilisateur, encore dans la file des non assignes. */
    NEW,
    /** Pris en charge par un admin, qui n'a pas encore repondu. */
    ASSIGNED,
    /** L'admin a repondu, la balle est dans le camp de l'utilisateur. */
    WAITING_USER,
    /** L'utilisateur a repondu, la balle est dans le camp du support. */
    WAITING_SUPPORT,
    /** Ferme. Plus aucun message n'est accepte. */
    RESOLVED
}
