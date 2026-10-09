package com.yadony.api.payments.overview;

/**
 * État normalisé d'un montant dans l'aperçu « Mon argent » (FLUTTER-HV). Dérivé en lecture seule
 * de {@code payments}, du colis, des litiges, de la garde « destinataire absent » et des opérations
 * pawaPay par {@link MoneyStateResolver} : rien n'est stocké.
 */
public enum MoneyState {
    /** Payé et en séquestre, colis pas encore remis au voyageur. */
    ESCROWED,
    /** Payé et en séquestre, colis remis, en route ou arrivé : attend la confirmation de livraison. */
    AWAITING_DELIVERY_CONFIRMATION,
    /** Garde « destinataire absent » en cours : libération automatique à {@code releaseAt} sans litige. */
    RELEASE_SCHEDULED,
    /** Litige ouvert sur le colis ou contestation bancaire (chargeback) : l'équipe tranche. */
    IN_DISPUTE,
    /** Versement retenu (bénéficiaire gelé, remboursement partiel, litige tranché) : l'équipe vérifie. */
    ON_HOLD,
    /** Libéré, versement en cours chez le prestataire (ou livraison confirmée, versement en préparation). */
    PAYOUT_IN_PROGRESS,
    /** Libéré au voyageur dans la fenêtre récente. */
    RELEASED_RECENTLY,
    /** Remboursement mobile money en cours vers l'expéditeur. */
    REFUND_PENDING,
    /** Remboursé à l'expéditeur dans la fenêtre récente (vue expéditeur seulement). */
    REFUNDED_RECENTLY,
    /** Colis réglé en espèces de la main à la main : rien ne transite par Yadony. */
    CASH
}
