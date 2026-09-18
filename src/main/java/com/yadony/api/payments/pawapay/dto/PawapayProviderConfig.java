package com.yadony.api.payments.pawapay.dto;

import com.yadony.api.payments.pawapay.PawapayProviders;
import java.math.BigDecimal;

/**
 * Configuration active d'un opérateur pour un pays/devise donné — aplatie depuis {@code GET /v2/active-conf}.
 * Sont lus le deposit, le payout et le refund ({@code operationTypes.REFUND} : l'opérateur accepte-t-il
 * le remboursement d'un dépôt ?) ; le nombre de décimales vient de {@code SupportedCurrency.minorUnit()},
 * jamais de pawaPay.
 */
public record PawapayProviderConfig(String provider, String countryAlpha3, String currency,
                                    Limits deposit, Limits payout, Limits refund) {

    /** Opérateur sans type {@code REFUND} déclaré : le remboursement d'un dépôt n'y est pas proposé. */
    public PawapayProviderConfig(String provider, String countryAlpha3, String currency,
                                 Limits deposit, Limits payout) {
        this(provider, countryAlpha3, currency, deposit, payout, null);
    }

    public record Limits(BigDecimal minAmount, BigDecimal maxAmount, String authType, String status) {
        public boolean isOperational() { return status == null || !"CLOSED".equalsIgnoreCase(status); }
    }

    public boolean supportsDeposit() { return deposit != null && deposit.isOperational(); }
    public boolean supportsPayout() { return payout != null && payout.isOperational(); }
    public boolean supportsRefund() { return refund != null && refund.isOperational(); }
    public boolean isRedirectDeposit() { return deposit != null && PawapayProviders.REDIRECT_AUTH.equals(deposit.authType()); }
}
