package com.yadony.api.admin.dto;

import java.math.BigDecimal;

/**
 * Corps facultatif de {@code POST /admin/wallet-refund-requests/{id}/retry}. Sans corps ou sans
 * {@code amount}, le montant suggéré par l'aperçu est versé ({@code min(dû, maxSûr)}).
 * {@code amount} : strictement positif, au plus le montant dû, à l'échelle de la devise (0
 * décimale pour XOF et XAF, 2 pour EUR), et au plus le montant sûr recalculé sur le solde pawaPay
 * relu juste avant l'envoi. Validé par {@code WalletRefundRetryService} (422 RFC 7807).
 */
public record AdminWalletRefundRetryRequest(BigDecimal amount) {
}
