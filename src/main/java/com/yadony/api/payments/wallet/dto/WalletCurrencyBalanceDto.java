package com.yadony.api.payments.wallet.dto;

import java.math.BigDecimal;

/**
 * Solde d'une devise. {@code refundableAmount} est le brut remboursable,
 * {@code refundFeeAmount} les frais retenus au remboursement et {@code refundNetAmount} ce qui
 * repart réellement vers l'utilisateur ({@code refundableAmount - refundFeeAmount}).
 * {@code refundEligible} n'est vrai que si ce net est positif. {@code estimatedInActive} est
 * l'équivalent de {@code balance} dans la devise active, purement informatif, {@code null} si
 * le taux manque. Les trois derniers champs sont additifs : un ancien client les ignore.
 */
public record WalletCurrencyBalanceDto(String currency, BigDecimal balance, boolean active, boolean refundEligible,
                                       BigDecimal refundableAmount, BigDecimal nonRefundableAmount,
                                       BigDecimal refundFeeAmount, BigDecimal refundNetAmount,
                                       BigDecimal estimatedInActive) {
}
