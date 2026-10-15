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
    ,
    /**
     * Solde débité automatiquement après confirmation Stripe d'un remboursement
     * self-service, distinct du zérotage manuel admin pour l'audit.
     */
    SELF_REFUND_OUT
    ,
    /**
     * Part non-cash du solde (parrainage, remboursements internes) débitée à la
     * finalisation d'une suppression de compte : sans PaymentIntent derrière, rien
     * n'est remboursable. Cf. UserFinalizedPaymentsListener.
     */
    FORFEITED_ON_DELETION
    ,
    /**
     * Correction manuelle du solde par un admin, au crédit (cf.
     * {@code WalletAdminAdjustmentService}). Hors TOP_UP à paymentRef, donc rangée dans le
     * non-cash par {@code WalletRefundAllocator} : jamais remboursable vers une carte.
     */
    ADMIN_CREDIT
    ,
    /**
     * Correction manuelle du solde par un admin, au débit. Débit ordinaire pour
     * {@code WalletRefundAllocator} : consomme le non-cash, puis les recharges LIFO.
     */
    ADMIN_DEBIT
    ,
    /**
     * Crédit obtenu en saisissant un code de recharge créé par un admin
     * (cf. {@code WalletTopupCodeRedemptionService}). Sans paymentRef, donc rangé dans le
     * non-cash par {@code WalletRefundAllocator} : jamais remboursable, perdu à la suppression.
     */
    TOP_UP_CODE
}
