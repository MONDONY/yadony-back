package com.yadony.api.payments.cash.dto;

import java.math.BigDecimal;

/**
 * Réponse d'une acceptation espèces. Sur {@code INSUFFICIENT_WALLET}, {@code availableBalance},
 * {@code requiredCommission} et {@code currency} sont TOUJOURS exprimés dans la devise active du
 * voyageur ; {@code breakdown} détaille la répartition entre portefeuille du colis et devise
 * active, et n'est renseigné que lorsque les deux devises diffèrent (omis du JSON sinon).
 */
public record AcceptBidResponse(
        AcceptanceStatusDto status,
        String clientSecret,
        String paymentIntentId,
        String error,
        BigDecimal availableBalance,
        BigDecimal requiredCommission,
        Boolean hasCard,
        String currency,
        CommissionShortfallDto breakdown
) {
    public static AcceptBidResponse accepted() {
        return new AcceptBidResponse(AcceptanceStatusDto.ACCEPTED, null, null, null, null, null, null, null, null);
    }

    public static AcceptBidResponse requires3ds(String clientSecret, String paymentIntentId) {
        return new AcceptBidResponse(AcceptanceStatusDto.REQUIRES_3DS, clientSecret, paymentIntentId, null,
                null, null, null, null, null);
    }

    public static AcceptBidResponse insufficientWallet(BigDecimal availableBalance, BigDecimal requiredCommission,
                                                        boolean hasCard, String currency) {
        return insufficientWallet(availableBalance, requiredCommission, hasCard, currency, null);
    }

    public static AcceptBidResponse insufficientWallet(BigDecimal availableBalance, BigDecimal requiredCommission,
                                                        boolean hasCard, String currency,
                                                        CommissionShortfallDto breakdown) {
        return new AcceptBidResponse(AcceptanceStatusDto.INSUFFICIENT_WALLET, null, null, null,
                availableBalance, requiredCommission, hasCard, currency, breakdown);
    }

    public static AcceptBidResponse failed(String error) {
        return new AcceptBidResponse(AcceptanceStatusDto.FAILED, null, null, error, null, null, null, null, null);
    }
}
