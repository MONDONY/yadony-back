package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyCatalogTest {

    private final CurrencyCatalog catalog = new CurrencyCatalog();

    @Test
    void resolves_a_supported_preference_whatever_its_case() {
        assertThat(catalog.resolve("cad")).isEqualTo(SupportedCurrency.CAD);
        assertThat(catalog.resolve("XOF")).isEqualTo(SupportedCurrency.XOF);
        assertThat(catalog.resolve(" eur ")).isEqualTo(SupportedCurrency.EUR);
    }

    @Test
    void falls_back_to_eur_when_no_preference_is_given() {
        assertThat(catalog.resolve(null)).isEqualTo(SupportedCurrency.EUR);
        assertThat(catalog.resolve("  ")).isEqualTo(SupportedCurrency.EUR);
    }

    @Test
    void rejects_an_unsupported_preference_as_a_business_error() {
        assertThatThrownBy(() -> catalog.resolve("JPY"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(error -> {
                    YadonyBusinessException exception = (YadonyBusinessException) error;
                    assertThat(exception.getErrorCode()).isEqualTo("unsupported-currency");
                });
    }

    @Test
    void fromCodeOrDefault_is_the_single_fallback_policy() {
        assertThat(SupportedCurrency.fromCodeOrDefault("xaf")).isEqualTo(SupportedCurrency.XAF);
        assertThat(SupportedCurrency.fromCodeOrDefault("JPY")).isEqualTo(SupportedCurrency.EUR);
        assertThat(SupportedCurrency.fromCodeOrDefault(null)).isEqualTo(SupportedCurrency.EUR);
    }

    @Test
    void exposes_iso_codes_and_stripe_minor_units() {
        assertThat(SupportedCurrency.EUR.code()).isEqualTo("eur");
        assertThat(SupportedCurrency.USD.minorUnit()).isEqualTo(2);
        assertThat(SupportedCurrency.CAD.minorUnit()).isEqualTo(2);
        assertThat(SupportedCurrency.GBP.minorUnit()).isEqualTo(2);
        assertThat(SupportedCurrency.CHF.minorUnit()).isEqualTo(2);
        assertThat(SupportedCurrency.XOF.minorUnit()).isZero();
        assertThat(SupportedCurrency.XAF.minorUnit()).isZero();
    }
}
