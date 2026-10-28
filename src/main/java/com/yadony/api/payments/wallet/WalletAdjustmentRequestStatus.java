package com.yadony.api.payments.wallet;

/** Demande d'ajustement admin soumise à la double validation. */
public enum WalletAdjustmentRequestStatus {
    /** En attente d'un second admin : rien n'est écrit au grand livre. */
    PENDING,
    /** Approuvée : le mouvement est écrit ({@code transactionId}). */
    APPROVED,
    /** Rejetée : aucun mouvement. */
    REJECTED
}
