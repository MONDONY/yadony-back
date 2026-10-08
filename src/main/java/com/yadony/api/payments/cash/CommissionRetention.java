package com.yadony.api.payments.cash;

/**
 * Commission espèces conservée quand le voyageur est l'auteur de l'annulation (FLUTTER-E4).
 * Seule l'action d'audit est partagée par les trois écouteurs de remboursement
 * (annulation d'un colis, annulation de trajet via portefeuille ou via carte).
 */
public final class CommissionRetention {

    /** Action audit_log écrite à la place du remboursement. */
    public static final String AUDIT_ACTION = "COMMISSION_RETAINED_TRAVELER_CANCEL";

    private CommissionRetention() {
    }
}
