package com.yadony.api.payments.overview.dto;

import java.math.BigDecimal;

/**
 * Totaux expéditeur d'une devise — jamais additionnés entre devises.
 *
 * @param blocked          payé et encore en séquestre
 * @param refundPending    remboursement en cours
 * @param refundedRecently remboursé dans la fenêtre récente
 */
public record SenderTotalDto(String currency, BigDecimal blocked, BigDecimal refundPending,
                             BigDecimal refundedRecently) {
}
