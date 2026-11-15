package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bornes dures du prix au m³ d'un trajet bateau (lot 4), mises à l'échelle de la devise du trajet. */
class PricePerM3BoundsTest {

    private static final BigDecimal MIN_EUR = new BigDecimal("100");
    private static final BigDecimal MAX_EUR = new BigDecimal("600");

    private static void assertAccepted(String price, SupportedCurrency currency) {
        assertThatCode(() -> PricePerM3Bounds.assertWithinBounds(
                price == null ? null : new BigDecimal(price), currency, MIN_EUR, MAX_EUR))
                .as(price + " " + currency).doesNotThrowAnyException();
    }

    private static void assertRefused(String price, SupportedCurrency currency, String reason, String min,
                                      String max, String currencyCode) {
        assertThatThrownBy(() -> PricePerM3Bounds.assertWithinBounds(
                new BigDecimal(price), currency, MIN_EUR, MAX_EUR))
                .as(price + " " + currency)
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("price-per-m3-out-of-bounds");
                    assertThat(ex.getProperties()).containsEntry("reason", reason)
                            .containsEntry("currency", currencyCode);
                    assertThat((BigDecimal) ex.getProperties().get("min")).isEqualByComparingTo(min);
                    assertThat((BigDecimal) ex.getProperties().get("max")).isEqualByComparingTo(max);
                });
    }

    @Test
    void euros_bornesIncluses() {
        assertAccepted("100", SupportedCurrency.EUR);
        assertAccepted("240", SupportedCurrency.EUR);
        assertAccepted("600", SupportedCurrency.EUR);
    }

    @Test
    void euros_tropBas_tooLow() {
        assertRefused("99.99", SupportedCurrency.EUR, "too-low", "100", "600", "EUR");
    }

    @Test
    void euros_tropHaut_tooHigh() {
        assertRefused("600.01", SupportedCurrency.EUR, "too-high", "100", "600", "EUR");
    }

    @Test
    void francsCfa_plancherArrondiVersLeHautPlafondVersLeBas() {
        // 100 € = 65 595,7 XOF → 65 596 ; 600 € = 393 574,2 XOF → 393 574.
        assertAccepted("65596", SupportedCurrency.XOF);
        assertAccepted("393574", SupportedCurrency.XOF);
        assertRefused("65595", SupportedCurrency.XOF, "too-low", "65596", "393574", "XOF");
        assertRefused("393575", SupportedCurrency.XOF, "too-high", "65596", "393574", "XOF");
    }

    @Test
    void prixEnEurosSaisiEnFrancsCfa_refuse() {
        // 240 tapés en croyant saisir des euros sur un trajet en F CFA.
        assertRefused("240", SupportedCurrency.XOF, "too-low", "65596", "393574", "XOF");
    }

    @Test
    void autreDeviseACentimes_miseALEchelle() {
        // 100 € = 108 $ et 600 € = 648 $.
        assertAccepted("108", SupportedCurrency.USD);
        assertRefused("107.99", SupportedCurrency.USD, "too-low", "108", "648", "USD");
    }

    @Test
    void prixAbsent_nonJuge() {
        assertAccepted(null, SupportedCurrency.EUR);
    }

    @Test
    void echelleReglable_plancherEtPlafond() {
        assertThat(CurrencyBounds.floorFromEur(new BigDecimal("100"), SupportedCurrency.XOF))
                .isEqualByComparingTo("65596");
        assertThat(CurrencyBounds.ceilingFromEur(new BigDecimal("600"), SupportedCurrency.XOF))
                .isEqualByComparingTo("393574");
        assertThat(CurrencyBounds.floorFromEur(new BigDecimal("100"), SupportedCurrency.EUR))
                .isEqualByComparingTo("100.00");
    }
}
