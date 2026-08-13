package com.yadony.api.payments.currency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SupportedCurrency — catalogue des devises acceptées")
class SupportedCurrencyTest {

    @Test
    @DisplayName("les codes exposés sont ceux qu'attend Stripe, en minuscules")
    void codesAreLowercaseForStripe() {
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            assertThat(currency.code()).isEqualTo(currency.code().toLowerCase());
        }
    }

    @Test
    void fromCodeAcceptsAnyCasingAndSurroundingSpaces() {
        assertThat(SupportedCurrency.fromCode("EUR")).isEqualTo(SupportedCurrency.EUR);
        assertThat(SupportedCurrency.fromCode("eur")).isEqualTo(SupportedCurrency.EUR);
        assertThat(SupportedCurrency.fromCode("  XoF ")).isEqualTo(SupportedCurrency.XOF);
    }

    @Test
    void fromCodeReturnsNullOnUnknownInput() {
        assertThat(SupportedCurrency.fromCode("ZZZ")).isNull();
        assertThat(SupportedCurrency.fromCode(null)).isNull();
        assertThat(SupportedCurrency.fromCode("  ")).isNull();
    }

    @Test
    @DisplayName("le repli est unique et vaut l'euro")
    void fromCodeOrDefaultFallsBackToEuro() {
        assertThat(SupportedCurrency.fromCodeOrDefault("ZZZ")).isEqualTo(SupportedCurrency.EUR);
        assertThat(SupportedCurrency.fromCodeOrDefault(null)).isEqualTo(SupportedCurrency.EUR);
        assertThat(SupportedCurrency.fromCodeOrDefault("xof")).isEqualTo(SupportedCurrency.XOF);
    }

    @Test
    @DisplayName("les francs CFA n'ont pas de sous-unité")
    void cfaFrancsHaveNoMinorUnit() {
        // Un montant en XOF exprimé en unités mineures vaut le montant lui-même :
        // c'est ce qui rendait faux tout calcul divisant par 100 en dur.
        assertThat(SupportedCurrency.XOF.minorUnit()).isZero();
        assertThat(SupportedCurrency.XAF.minorUnit()).isZero();
        assertThat(SupportedCurrency.EUR.minorUnit()).isEqualTo(2);
    }

    @Test
    @DisplayName("le facteur d'échelle est positif et vaut 1 pour l'euro")
    void boundScaleIsAnchoredOnTheEuro() {
        assertThat(SupportedCurrency.EUR.boundScale()).isEqualTo(1);
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            assertThat(currency.boundScale())
                    .as("facteur d'échelle de %s", currency)
                    .isPositive();
        }
        // Parité fixe avec l'euro : ce n'est pas une estimation de marché.
        assertThat(SupportedCurrency.XOF.boundScale()).isEqualTo(655);
        assertThat(SupportedCurrency.XAF.boundScale()).isEqualTo(655);
    }
}
