package com.yadony.api.admin.dto;

import com.yadony.api.payments.wallet.WalletRefundRetryService;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Réponse de {@code GET /admin/wallet-refund-requests/{id}/retry-preview}.
 *
 * <ul>
 *   <li>{@code amountDue} : net que le flux automatique enverrait aujourd'hui (brut moins le frais
 *       du barème pawaPay actuel) ;</li>
 *   <li>{@code walletBalance} : solde du wallet pawaPay de {@code country} / {@code currency} ;</li>
 *   <li>{@code maxSafeAmount} : {@code floor(walletBalance / (1 + refundRatePercent / 100))} ;</li>
 *   <li>{@code suggestedAmount} : {@code min(amountDue, maxSafeAmount)} ;</li>
 *   <li>{@code topUpNeeded} : ce qu'il manque au wallet pawaPay pour verser le dû, 0 si rien ;</li>
 *   <li>{@code msisdnMasked} : numéro de la recharge, toujours masqué.</li>
 * </ul>
 * Solde pawaPay illisible : {@code walletBalance}, {@code maxSafeAmount}, {@code suggestedAmount}
 * et {@code topUpNeeded} sont nuls, {@code retryAvailable} vaut {@code false} et
 * {@code unavailableReason} {@code pawapay-balance-unavailable}. Wallet trop bas pour verser
 * quoi que ce soit : {@code unavailableReason} {@code pawapay-insufficient-funds}.
 */
public record AdminWalletRefundRetryPreviewResponse(
        UUID requestId,
        String currency,
        String country,
        String provider,
        String msisdnMasked,
        BigDecimal grossAmount,
        BigDecimal amountDue,
        BigDecimal walletBalance,
        BigDecimal maxSafeAmount,
        BigDecimal suggestedAmount,
        BigDecimal topUpNeeded,
        BigDecimal refundRatePercent,
        boolean retryAvailable,
        String unavailableReason
) {
    public static AdminWalletRefundRetryPreviewResponse from(WalletRefundRetryService.Preview p) {
        return new AdminWalletRefundRetryPreviewResponse(p.requestId(), p.currency(), p.country(), p.provider(),
                p.msisdnMasked(), p.grossAmount(), p.amountDue(), p.walletBalance(), p.maxSafeAmount(),
                p.suggestedAmount(), p.topUpNeeded(), p.refundRatePercent(), p.retryAvailable(),
                p.unavailableReason());
    }
}
