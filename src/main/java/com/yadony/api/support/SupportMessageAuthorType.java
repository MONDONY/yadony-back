package com.yadony.api.support;

/**
 * Origine d'un message du fil. {@code authorId} pointe vers {@code users} pour
 * {@link #USER} et vers {@code admin_users} pour {@link #ADMIN} : deux tables
 * distinctes, d'ou la necessite de conserver le type a cote de l'identifiant.
 */
public enum SupportMessageAuthorType {
    USER,
    ADMIN,
    /** Ligne de journal posee par la plateforme (ex. fermeture par l'utilisateur) ; {@code authorId} = l'utilisateur concerne. */
    SYSTEM
}
