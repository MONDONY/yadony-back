package com.yadony.api.payments.wallet;

public enum WalletTransactionType {
    TOP_UP,
    BID_PAYMENT,
    COMMISSION_DEDUCTED,
    REFUND,
    REFERRAL_REWARD,
    /**
     * Solde remis à zéro par un admin après remboursement manuel hors-app (Stripe)
     * du solde rechargé par carte, pour débloquer une suppression de compte.
     * Cf. {@code WalletRefundRequestService}.
     */
    ADMIN_REFUND_OUT
}
