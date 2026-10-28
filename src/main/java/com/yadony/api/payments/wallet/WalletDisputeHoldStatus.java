package com.yadony.api.payments.wallet;

/** Issue d'un litige carte sur une recharge du portefeuille. */
public enum WalletDisputeHoldStatus {
    /** Litige ouvert : la devise est gelée. */
    OPEN,
    /** Litige gagné par Yadony : le montant débité est recrédité. */
    WON,
    /** Litige perdu : le débit reste, le reste dû éventuel est suivi par l'admin. */
    LOST
}
