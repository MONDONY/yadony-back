package com.yadony.api.payments.wallet;

public enum WalletRefundChannel {
    AUTOMATIC_STRIPE,
    AUTOMATIC_PAWAPAY,
    /**
     * Recharges carte ET mobile money dans la même devise : chaque item repart automatiquement
     * vers son rail d'origine (Stripe pour un {@code pi_...}, pawaPay pour un
     * {@code pawapay:<dépôt>}), dans une seule demande. Remplace l'ancien repli sur un ticket
     * manuel, qui portait tout le solde, non-cash compris, sans destination.
     */
    AUTOMATIC_MIXED,
    MANUAL_ADMIN
}
