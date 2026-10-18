package com.yadony.api.admin.dto;

import java.math.BigDecimal;

/**
 * Corps facultatif de {@code POST /admin/wallet-refund-requests/{id}/retry}. La relance verse
 * toujours le montant dû entier, jamais une partie. {@code amount}, s'il est donné, confirme le
 * montant affiché par l'aperçu : il doit égaler le dû, à l'échelle de la devise (0 décimale pour
 * XOF et XAF, 2 pour EUR), sinon 422 RFC 7807 ({@code wallet-refund-retry-partial-not-allowed},
 * {@code -amount-too-high}, {@code -amount-scale}, {@code -amount-invalid}). Le dû doit aussi
 * tenir dans le montant sûr recalculé sur le solde pawaPay relu juste avant l'envoi, sinon 422
 * {@code wallet-refund-insufficient-pawapay-funds} avec {@code topUpNeeded}.
 */
public record AdminWalletRefundRetryRequest(BigDecimal amount) {
}
