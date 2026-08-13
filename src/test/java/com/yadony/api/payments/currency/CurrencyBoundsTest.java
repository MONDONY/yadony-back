package com.yadony.api.payments.currency;

import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CurrencyBounds — bornes de validation par devise")
class CurrencyBoundsTest {

    @Test
    void euroKeepsTheHistoricalBounds() {
        assertThat(CurrencyBounds.maxPricePerKg(SupportedCurrency.EUR)).isEqualByComparingTo("500.00");
        assertThat(CurrencyBounds.maxNegotiationPrice(SupportedCurrency.EUR)).isEqualByComparingTo("500.00");
        assertThat(CurrencyBounds.maxPackageBudget(SupportedCurrency.EUR)).isEqualByComparingTo("560.00");
        assertThat(CurrencyBounds.minTopup(SupportedCurrency.EUR)).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("le plafond XOF permet enfin un tarif réaliste")
    void xofBoundsAreScaledAndWholeNumbered() {
        // 500 XOF/kg valaient 0,76 €/kg : un voyageur en franc CFA ne pouvait pas
        // publier un tarif réaliste. Le plafond suit désormais la parité fixe.
        assertThat(CurrencyBounds.maxPricePerKg(SupportedCurrency.XOF))
                .isEqualByComparingTo("327978");
        // XOF n'a pas de sous-unité : les bornes doivent rester entières.
        assertThat(CurrencyBounds.maxPricePerKg(SupportedCurrency.XOF).scale()).isZero();
        assertThat(CurrencyBounds.minTopup(SupportedCurrency.XOF)).isEqualByComparingTo("656");
    }

    @Test
    @DisplayName("le plus petit montant représentable suit la sous-unité")
    void smallestUnitFollowsMinorUnit() {
        assertThat(CurrencyBounds.smallestUnit(SupportedCurrency.EUR)).isEqualByComparingTo("0.01");
        // Un plancher à 0,01 était impossible à honorer en XOF.
        assertThat(CurrencyBounds.smallestUnit(SupportedCurrency.XOF)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("un plafond est arrondi vers le bas, un plancher vers le haut")
    void roundingFollowsTheDirectionOfTheBound() {
        for (SupportedCurrency currency : SupportedCurrency.values()) {
            assertThat(CurrencyBounds.maxPricePerKg(currency).scale()).isEqualTo(currency.minorUnit());
            assertThat(CurrencyBounds.minTopup(currency)).isPositive();
        }
    }

    @Test
    @DisplayName("un barème en euros garde sa valeur une fois exprimé en devise")
    void scaleFromEurKeepsTheValueOfTheScale() {
        // 5 € de récompense valent environ 3 280 F CFA. Verser 5 XOF, soit moins
        // d'un centime d'euro, aurait vidé la promesse de tout contenu.
        assertThat(CurrencyBounds.scaleFromEur(new BigDecimal("5"), SupportedCurrency.XOF))
                .isEqualByComparingTo("3280");
        assertThat(CurrencyBounds.scaleFromEur(new BigDecimal("5"), SupportedCurrency.EUR))
                .isEqualByComparingTo("5.00");
        // Le résultat respecte la précision de la devise : pas de centimes en XOF.
        assertThat(CurrencyBounds.scaleFromEur(new BigDecimal("5"), SupportedCurrency.XOF).scale())
                .isZero();
    }

}
