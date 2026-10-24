package com.yadony.api.admin.dto;

/**
 * Nombres « à traiter » des quatre sections Transactions : {@code payments} (paiements en alerte),
 * {@code mobileMoney} (opérations mobile money à reprendre), {@code soldes} (remboursements du solde en attente
 * + commissions cash à traiter) et {@code chargebacks} (litiges ouverts). {@code details} donne les nombres
 * dont ils sont la somme, pour afficher la ventilation de « Soldes ».
 */
public record AdminFinanceCountsResponse(long payments, long mobileMoney, long soldes, long chargebacks,
                                         Details details) {

    public record Details(long paymentsAttention, long mobileMoneyAttention, long walletRefundsPending,
                          long cashCommissionsAttention, long chargebacksOpen) {
    }
}
