package com.yadony.api.admin.account;

/**
 * Admin permissions enum (Task 2).
 * 26 granular permissions for role-based access control.
 */
public enum AdminPermission {
    // Account management
    ADMIN_MANAGE,

    // Metrics & reporting
    METRICS_VIEW,

    // User management (7 permissions)
    USER_VIEW,
    USER_SUSPEND,
    USER_BAN,
    USER_KYC,
    USER_GDPR_DELETE,
    USER_COMMISSION,

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

    // Content & operations
    PROMO_MANAGE,
    AUDIT_VIEW,
    EXPORT_RUN
}
