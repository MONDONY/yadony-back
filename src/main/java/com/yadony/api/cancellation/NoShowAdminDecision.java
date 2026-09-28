package com.yadony.api.cancellation;

/**
 * Décision d'un administrateur sur une déclaration de no-show
 * ({@code cancellations.admin_decision}, V273). CONFIRMED : la déclaration est
 * retenue ({@code no_show_status = CONFIRMED}). REJECTED : elle est classée
 * ({@code no_show_status = RESOLVED}), le bid continue normalement.
 */
public enum NoShowAdminDecision {
    CONFIRMED,
    REJECTED
}
