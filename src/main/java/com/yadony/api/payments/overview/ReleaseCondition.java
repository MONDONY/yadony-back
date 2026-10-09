package com.yadony.api.payments.overview;

/** Condition (ou issue) de libération d'un montant, à traduire côté client. */
public enum ReleaseCondition {
    /** Libéré à la confirmation de livraison (code de remise saisi ou scan). */
    ON_DELIVERY_CONFIRMATION,
    /** Libération automatique à la fin de la garde « destinataire absent », s'il n'y a aucun litige. */
    AUTO_RELEASE_AFTER_HOLD_IF_NO_DISPUTE,
    /** L'équipe Yadony tranche (litige, contestation bancaire). */
    ADMIN_DECISION,
    /** L'équipe Yadony vérifie avant tout versement (versement retenu). */
    ADMIN_REVIEW,
    /** Versement en cours de traitement par le prestataire de paiement. */
    PAYOUT_PROCESSING,
    /** Déjà libéré. */
    RELEASED,
    /** Remboursement en cours de traitement. */
    REFUND_PROCESSING,
    /** Déjà remboursé. */
    REFUNDED,
    /** Paiement en espèces : rien à recevoir via Yadony. */
    CASH_IN_PERSON
}
