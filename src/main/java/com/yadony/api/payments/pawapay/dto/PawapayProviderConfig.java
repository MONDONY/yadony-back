package com.yadony.api.payments.pawapay.dto;

import com.yadony.api.payments.pawapay.PawapayProviders;
import java.math.BigDecimal;

/**
 * Configuration active d'un opérateur pour un pays/devise donné — aplatie depuis {@code GET /v2/active-conf}.
 * Seuls le deposit et le payout sont lus ; le nombre de décimales vient de
 * {@code SupportedCurrency.minorUnit()}, jamais de pawaPay.
 */
public record PawapayProviderConfig(String provider, String countryAlpha3, String currency,
                                    Limits deposit, Limits payout) {

    public record Limits(BigDecimal minAmount, BigDecimal maxAmount, String authType, String status) {
        public boolean isOperational() { return status == null || !"CLOSED".equalsIgnoreCase(status); }
    }

    public boolean supportsDeposit() { return deposit != null && deposit.isOperational(); }
    public boolean supportsPayout() { return payout != null && payout.isOperational(); }
    public boolean isRedirectDeposit() { return deposit != null && PawapayProviders.REDIRECT_AUTH.equals(deposit.authType()); }
}
