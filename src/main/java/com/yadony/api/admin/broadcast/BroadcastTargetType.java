package com.yadony.api.admin.broadcast;

/**
 * Segments de destinataires d'un broadcast.
 *
 * <p>⚠️ Le ciblage est <b>comportemental</b>, jamais fonde sur {@code user_roles} :
 * depuis la migration {@code V193}, tout utilisateur porte simultanement
 * {@code SENDER} et {@code TRAVELER} ({@code AuthService:89,399}). Un filtre par role
 * enverrait donc a 100 % des comptes dans les deux cas, silencieusement.
 */
public enum BroadcastTargetType {
    /** Tous les comptes actifs. */
    ALL,
    /** A cree au moins un bid. */
    SENDERS,
    /** A publie au moins une annonce. */
    TRAVELERS,
    /** A publie une annonce ou un bid sur le corridor (ville de depart -> ville d'arrivee). */
    CORRIDOR,
    /** Un utilisateur designe. */
    USER
}
