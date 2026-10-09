package com.yadony.api.payments.currency;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PricePerKgBounds — refus d'un prix au kilo hors bornes (FLUTTER-GK)")
class PricePerKgBoundsTest {

    @Test
    void belowTheFloor_isRefusedWithItsBounds() {
        assertThatThrownBy(() -> PricePerKgBounds.assertWithinBounds(
                new BigDecimal("8"), SupportedCurrency.XOF, "price-out-of-bounds"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(e.getErrorCode()).isEqualTo("price-out-of-bounds");
                    assertThat(e.getProperties())
                            .containsEntry("reason", "too-low")
                            .containsEntry("currency", "XOF")
                            .containsEntry("min", new BigDecimal("656"))
                            .containsEntry("max", new BigDecimal("327978"));
                });
    }

    @Test
    void aboveTheCeiling_isRefusedWithTheGivenCode() {
        assertThatThrownBy(() -> PricePerKgBounds.assertWithinBounds(
                501.0, SupportedCurrency.EUR, "trip-template/price-out-of-bounds"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("trip-template/price-out-of-bounds");
                    assertThat(e.getProperties()).containsEntry("reason", "too-high");
                });
    }

    @Test
    void boundsAreInclusive_andAbsentOrZeroPricesAreLeftToTheCaller() {
        assertThatCode(() -> {
            PricePerKgBounds.assertWithinBounds(new BigDecimal("1"), SupportedCurrency.EUR, "c");
            PricePerKgBounds.assertWithinBounds(new BigDecimal("500"), SupportedCurrency.EUR, "c");
            PricePerKgBounds.assertWithinBounds(new BigDecimal("656"), SupportedCurrency.XOF, "c");
            PricePerKgBounds.assertWithinBounds((BigDecimal) null, SupportedCurrency.EUR, "c");
            PricePerKgBounds.assertWithinBounds((Double) null, SupportedCurrency.EUR, "c");
            PricePerKgBounds.assertWithinBounds(BigDecimal.ZERO, SupportedCurrency.EUR, "c");
        }).doesNotThrowAnyException();
        assertThatThrownBy(() -> PricePerKgBounds.assertWithinBounds(
                new BigDecimal("0.99"), SupportedCurrency.EUR, "c"))
                .isInstanceOf(YadonyBusinessException.class);
    }
}
