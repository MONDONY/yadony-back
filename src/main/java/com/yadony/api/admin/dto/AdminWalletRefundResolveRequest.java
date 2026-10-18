package com.yadony.api.admin.dto;

import java.math.BigDecimal;

/**
 * Corps facultatif de {@code POST /admin/wallet-refund-requests/{id}/resolve}. Sans corps, la
 * résolution se fait comme avant. {@code paidAmount} : montant réellement versé hors app, dans la
 * devise de la demande, strictement positif, au plus le montant demandé, sans plus de décimales
 * que la devise n'en permet (0 pour XOF et XAF, 2 pour EUR). {@code note} : 500 caractères au
 * plus. Les deux sont validés par {@code WalletRefundRequestService} (422 RFC 7807) et
 * n'influent jamais sur le débit du wallet.
 */
public record AdminWalletRefundResolveRequest(BigDecimal paidAmount, String note) {
}
