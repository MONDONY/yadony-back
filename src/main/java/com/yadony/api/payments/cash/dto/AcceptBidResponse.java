package com.yadony.api.payments.cash.dto;

import java.math.BigDecimal;

public record AcceptBidResponse(
        AcceptanceStatusDto status,
        String clientSecret,
        String paymentIntentId,
        String error,
        BigDecimal availableBalance,
        BigDecimal requiredCommission,
        Boolean hasCard,
        String currency
) {
    public static AcceptBidResponse accepted() {
        return new AcceptBidResponse(AcceptanceStatusDto.ACCEPTED, null, null, null, null, null, null, null);
    }

    public static AcceptBidResponse requires3ds(String clientSecret, String paymentIntentId) {
        return new AcceptBidResponse(AcceptanceStatusDto.REQUIRES_3DS, clientSecret, paymentIntentId, null, null, null, null, null);
    }

    public static AcceptBidResponse insufficientWallet(BigDecimal availableBalance, BigDecimal requiredCommission,
                                                        boolean hasCard, String currency) {
        return new AcceptBidResponse(AcceptanceStatusDto.INSUFFICIENT_WALLET, null, null, null,
                availableBalance, requiredCommission, hasCard, currency);
    }

    public static AcceptBidResponse failed(String error) {
        return new AcceptBidResponse(AcceptanceStatusDto.FAILED, null, null, error, null, null, null, null);
    }
}
