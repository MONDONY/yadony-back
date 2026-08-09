package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CurrencyCatalogTest {

    private final CurrencyCatalog catalog = new CurrencyCatalog();

    @Test
    void resolves_default_currency_for_supported_countries() {
        assertThat(catalog.resolve("US", null)).isEqualTo(SupportedCurrency.USD);
        assertThat(catalog.resolve("CA", null)).isEqualTo(SupportedCurrency.CAD);
        assertThat(catalog.resolve("FR", null)).isEqualTo(SupportedCurrency.EUR);
        assertThat(catalog.resolve("SN", null)).isEqualTo(SupportedCurrency.XOF);
        assertThat(catalog.resolve("CI", null)).isEqualTo(SupportedCurrency.XOF);
        assertThat(catalog.resolve("CM", null)).isEqualTo(SupportedCurrency.XAF);
    }

    @Test
    void falls_back_to_eur_for_an_unconfigured_country() {
        assertThat(catalog.resolve("JP", null)).isEqualTo(SupportedCurrency.EUR);
    }

    @Test
    void explicit_supported_preference_has_priority_over_country_default() {
        assertThat(catalog.resolve("US", "cad")).isEqualTo(SupportedCurrency.CAD);
    }

    @Test
    void rejects_an_unsupported_preference_as_a_business_error() {
        assertThatThrownBy(() -> catalog.resolve("US", "JPY"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(error -> {
                    YadonyBusinessException exception = (YadonyBusinessException) error;
                    assertThat(exception.getErrorCode()).isEqualTo("unsupported-currency");
                });
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
