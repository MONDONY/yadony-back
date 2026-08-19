package com.yadony.api.payments.currency;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CurrencyAmountTest {

    @Test
    void converts_two_decimal_currency_to_minor_units_with_half_up_rounding() {
        CurrencyAmount amount = CurrencyAmount.of(new BigDecimal("12.345"), SupportedCurrency.EUR);

        assertThat(amount.major()).isEqualByComparingTo("12.35");
        assertThat(amount.minor()).isEqualTo(1235L);
        assertThat(amount.currency()).isEqualTo(SupportedCurrency.EUR);
    }

    @Test
    void converts_zero_decimal_currency_without_fractional_minor_units() {
        CurrencyAmount amount = CurrencyAmount.of(new BigDecimal("5100.50"), SupportedCurrency.XOF);

        assertThat(amount.major()).isEqualByComparingTo("5101");
        assertThat(amount.minor()).isEqualTo(5101L);
    }

    @Test
    void preserves_eur_identity_at_two_decimals() {
        CurrencyAmount amount = CurrencyAmount.of(new BigDecimal("35.00"), SupportedCurrency.EUR);

        assertThat(amount.minor()).isEqualTo(3500L);
    }
}
