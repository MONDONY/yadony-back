package com.yadony.api.auth.dto;

import com.yadony.api.payments.wallet.WalletRefundRequestEntity;

import java.math.BigDecimal;

/**
 * Story — solde wallet non nul bloquant la suppression : ticket ouvert pour un
 * admin (cf. {@code WalletRefundRequestService}).
 */
public record WalletRefundRequestResponse(String currency, BigDecimal amount) {

    public static WalletRefundRequestResponse from(WalletRefundRequestEntity entity) {
        return new WalletRefundRequestResponse(entity.getCurrency(), entity.getAmount());
    }
}
