package com.yadony.api.admin.dto;

import com.yadony.api.payments.wallet.WalletRefundRetryService;
import java.math.BigDecimal;

/**
 * Réponse de {@code POST /admin/wallet-refund-requests/{id}/retry} : la demande, passée
 * {@code PROCESSING}, et les chiffres retenus pour l'envoi (montant versé, dû, solde pawaPay lu,
 * montant sûr). L'issue arrive ensuite par pawaPay : REFUNDED, ou FAILED avec un nouveau ticket
 * manuel.
 */
public record AdminWalletRefundRetryResponse(
        AdminWalletRefundRequestResponse request,
        BigDecimal amount,
        BigDecimal amountDue,
        BigDecimal walletBalance,
        BigDecimal maxSafeAmount
) {
    public static AdminWalletRefundRetryResponse from(WalletRefundRetryService.Retried r) {
        return new AdminWalletRefundRetryResponse(AdminWalletRefundRequestResponse.from(r.request()), r.amount(),
                r.amountDue(), r.walletBalance(), r.maxSafeAmount());
    }
}
