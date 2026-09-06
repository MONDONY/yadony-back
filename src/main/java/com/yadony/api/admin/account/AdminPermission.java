package com.yadony.api.admin.account;

/**
 * Admin permissions enum (Task 2).
 * 33 granular permissions for role-based access control.
 */
public enum AdminPermission {
    // Account management
    ADMIN_MANAGE,

    // Metrics & reporting
    METRICS_VIEW,

    // User management (8 permissions)
    USER_VIEW,
    USER_SUSPEND,
    USER_BAN,
    USER_KYC,
    USER_GDPR_DELETE,
    /** Suppression d'un compte décidée par l'administrateur, distincte d'une demande RGPD reçue. */
    USER_DELETE,
    USER_COMMISSION,
    /**
     * Offrir ou révoquer un accès PRO gratuit — geste commercial de même portée que
     * {@link #USER_COMMISSION}, donc accordée aux mêmes rôles.
     */
    USER_PRO_GRANT,

    // Payment management
    PAYMENT_VIEW,
    PAYMENT_RELEASE,
    PAYMENT_REFUND,

    // Bid management
    BID_VIEW,

    // Dispute management
    DISPUTE_VIEW,
    DISPUTE_RESOLVE,

    // Alerts & moderation
    ALERT_VIEW,
    ALERT_RESOLVE,
    MODERATION_VIEW,
    MESSAGE_DELETE,
    CONTENT_REMOVE,
    USER_MESSAGE_MUTE,

    // Reporting & ratings
    REPORT_VIEW,
    REPORT_RESOLVE,
    RATING_MODERATE,
    /**
     * Lot C — suppression definitive d'un avis, detachee de RATING_MODERATE.
     * Le support consulte et exclut ; effacer pour de bon reste a ADMIN et SUPER_ADMIN.
     */
    RATING_DELETE,

    /**
     * Lot D — envoi d'un broadcast de notifications (push + in-app) a un segment
     * d'utilisateurs. Jamais accordee au support : un envoi de masse est irreversible.
     */
    NOTIFICATION_SEND,

    /**
     * Lot D — modification des parametres plateforme (commission globale, seuil
     * d'urgence, plafond de remboursement, activation des SMS). Jamais accordee au
     * support : couper les SMS coupe aussi l'authentification par OTP.
     */
    CONFIG_MANAGE,

    /**
     * Lecture de la file des tickets support et du fil d'un ticket. Accordee au
     * support : c'est son metier.
     */
    SUPPORT_TICKET_VIEW,

    /**
     * S'assigner un ticket, le reassigner, y repondre, le resoudre. Separee de
     * {@link #SUPPORT_TICKET_VIEW} pour qu'un profil lecture seule (audit,
     * direction) puisse consulter la file sans jamais ecrire a un utilisateur.
     */
    SUPPORT_TICKET_MANAGE,

    // Content & operations
    PROMO_MANAGE,
    AUDIT_VIEW,
    EXPORT_RUN
}
