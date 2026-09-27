package com.yadony.api.payments.wallet;

/** Sens d'une correction de solde admin (cf. {@link WalletAdminAdjustmentService}). */
public enum WalletAdjustmentDirection {
    CREDIT,
    DEBIT;

    WalletTransactionType transactionType() {
        return this == CREDIT ? WalletTransactionType.ADMIN_CREDIT : WalletTransactionType.ADMIN_DEBIT;
    }
}
