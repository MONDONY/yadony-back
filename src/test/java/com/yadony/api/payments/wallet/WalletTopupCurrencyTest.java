package com.yadony.api.payments.wallet;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.yadony.api.payments.currency.ExchangeRateProperties;
import com.yadony.api.payments.currency.FxRateService;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.payments.wallet.dto.WalletTopupRequest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class WalletTopupCurrencyTest {

    @Test
    void topup_request_accepts_local_currency_without_changing_payment_method() {
        WalletTopupRequest request = new WalletTopupRequest();
        request.setAmount(new BigDecimal("50.00"));
        request.setPaymentMethod("STRIPE");
        request.setCurrencyCode("CAD");

        assertThat(request.getCurrencyCode()).isEqualTo("CAD");
        assertThat(request.getPaymentMethod()).isEqualTo("STRIPE");
    }

    @Test
    void local_currency_topup_converts_back_to_closed_wallet_eur_balance() {
        FxRateService fx = new FxRateService(
                (source, target) -> new BigDecimal("1.50"),
                new ExchangeRateProperties(new BigDecimal("655.957"), new BigDecimal("655.957"), 300),
                Caffeine.newBuilder().build());

        assertThat(fx.convertToEur(new BigDecimal("15.00"), SupportedCurrency.CAD).major())
                .isEqualByComparingTo("10.00");
    }
}
