package com.yadony.api.payments.currency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SupportedCurrency — catalogue des devises acceptées")
class SupportedCurrencyTest {

    @Test
    @DisplayName("le catalogue est fermé à EUR, XOF, XAF depuis le 2026-08-19")
    void catalogIsClosedToThreeCurrencies() {
        // USD/CAD/GBP/CHF retirés (zéro compte réel en prod à cette date) — voir
        // docs/specs/2026-08-19-plan-implementation-multidevise.md, lot 1.
        assertThat(SupportedCurrency.values())
                .containsExactlyInAnyOrder(
                        SupportedCurrency.EUR, SupportedCurrency.XOF, SupportedCurrency.XAF);
    }

    @Test
    @DisplayName("un code retiré du catalogue (ex. usd) retombe sur EUR sans exception")
    void removedCodeFallsBackToEur() {
        assertThat(SupportedCurrency.fromCode("usd")).isNull();
        assertThat(SupportedCurrency.fromCodeOrDefault("usd")).isEqualTo(SupportedCurrency.EUR);
    }

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
    @DisplayName("le taux est ancré sur l'euro et strictement positif")
    void unitsPerEurIsAnchoredOnTheEuro() {
        assertThat(SupportedCurrency.EUR.unitsPerEur()).isEqualByComparingTo("1");
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            assertThat(currency.unitsPerEur())
                    .as("taux de %s", currency)
                    .isPositive();
        }
        // Parité fixe avec l'euro, fixée par traité : ce n'est pas une estimation
        // de marché et la valeur ne dérive pas.
        assertThat(SupportedCurrency.XOF.unitsPerEur()).isEqualByComparingTo("655.957");
        assertThat(SupportedCurrency.XAF.unitsPerEur()).isEqualByComparingTo("655.957");
    }
}
