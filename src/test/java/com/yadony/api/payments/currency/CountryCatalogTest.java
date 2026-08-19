package com.yadony.api.payments.currency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CountryCatalogTest {

    @Test
    @DisplayName("Chaque zone monetaire mappe sur sa devise")
    void mapsCountriesToTheirCurrency() {
        assertThat(CountryCatalog.currencyOf("FR")).isEqualTo(SupportedCurrency.EUR);
        assertThat(CountryCatalog.currencyOf("CH")).isEqualTo(SupportedCurrency.CHF);
        assertThat(CountryCatalog.currencyOf("GB")).isEqualTo(SupportedCurrency.GBP);
        assertThat(CountryCatalog.currencyOf("CA")).isEqualTo(SupportedCurrency.CAD);
        assertThat(CountryCatalog.currencyOf("US")).isEqualTo(SupportedCurrency.USD);
        assertThat(CountryCatalog.currencyOf("SN")).isEqualTo(SupportedCurrency.XOF);
        assertThat(CountryCatalog.currencyOf("CM")).isEqualTo(SupportedCurrency.XAF);
    }

    @Test
    @DisplayName("Le code est normalise, un pays absent ne leve pas")
    void normalizesAndTolerates() {
        assertThat(CountryCatalog.currencyOf("fr")).isEqualTo(SupportedCurrency.EUR);
        assertThat(CountryCatalog.currencyOf(" ca ")).isEqualTo(SupportedCurrency.CAD);
        assertThat(CountryCatalog.currencyOf("ZZ")).isNull();
        assertThat(CountryCatalog.currencyOf(null)).isNull();
        assertThat(CountryCatalog.isSupported("ZZ")).isFalse();
    }

    @Test
    @DisplayName("Le catalogue couvre 38 pays et n'oublie aucune devise")
    void coversEveryCurrency() {
        assertThat(CountryCatalog.all()).hasSize(38);
        assertThat(CountryCatalog.all().stream().map(CountryCatalog::currencyOf).distinct())
                .containsExactlyInAnyOrder(SupportedCurrency.values());
    }
}
