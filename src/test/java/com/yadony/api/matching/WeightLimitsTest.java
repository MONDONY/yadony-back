package com.yadony.api.matching;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static com.yadony.api.matching.TransportMode.BOAT;
import static com.yadony.api.matching.TransportMode.BUS;
import static com.yadony.api.matching.TransportMode.CAR;
import static com.yadony.api.matching.TransportMode.OTHER;
import static com.yadony.api.matching.TransportMode.PLANE;
import static com.yadony.api.matching.TransportMode.TRAIN;
import static com.yadony.api.matching.WeightLimits.maxRecurrenceCapacity;
import static com.yadony.api.matching.WeightLimits.maxRequestWeight;
import static com.yadony.api.matching.WeightLimits.maxTripCapacity;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WeightLimits : plafonds de poids par mode")
class WeightLimitsTest {

    @Test
    @DisplayName("modes lourds : voiture, bus, bateau ; jamais avion, train, autre ni « peu importe »")
    void isHeavyMode() {
        assertThat(WeightLimits.isHeavyMode(CAR)).isTrue();
        assertThat(WeightLimits.isHeavyMode(BUS)).isTrue();
        assertThat(WeightLimits.isHeavyMode(BOAT)).isTrue();
        assertThat(WeightLimits.isHeavyMode(PLANE)).isFalse();
        assertThat(WeightLimits.isHeavyMode(TRAIN)).isFalse();
        assertThat(WeightLimits.isHeavyMode(OTHER)).isFalse();
        assertThat(WeightLimits.isHeavyMode(null)).isFalse();
    }

    @Test
    @DisplayName("demande : 32 kg en avion et « peu importe », 999 999,99 kg en route et bateau")
    void request_byMode() {
        assertThat(maxRequestWeight(null)).isEqualByComparingTo("32");
        assertThat(maxRequestWeight(PLANE)).isEqualByComparingTo("32");
        assertThat(maxRequestWeight(TRAIN)).isEqualByComparingTo("32");
        assertThat(maxRequestWeight(OTHER)).isEqualByComparingTo("32");
        assertThat(maxRequestWeight(CAR)).isEqualByComparingTo("999999.99");
        assertThat(maxRequestWeight(BUS)).isEqualByComparingTo("999999.99");
        assertThat(maxRequestWeight(BOAT)).isEqualByComparingTo("999999.99");
    }

    @Test
    @DisplayName("trajet : 999,99 kg en avion, 999 999,99 kg en route et bateau")
    void trip_byMode() {
        assertThat(maxTripCapacity(PLANE)).isEqualByComparingTo("999.99");
        assertThat(maxTripCapacity(null)).isEqualByComparingTo("999.99");
        assertThat(maxTripCapacity(BUS)).isEqualByComparingTo("999999.99");
        assertThat(maxTripCapacity(BOAT)).isEqualByComparingTo("999999.99");
    }

    @Test
    @DisplayName("série : 40 kg en avion et train, 999 999,99 kg en route")
    void recurrence_byMode() {
        assertThat(maxRecurrenceCapacity(TRAIN)).isEqualByComparingTo("40");
        assertThat(maxRecurrenceCapacity(PLANE)).isEqualByComparingTo("40");
        assertThat(maxRecurrenceCapacity(CAR)).isEqualByComparingTo("999999.99");
    }

    @Test
    @DisplayName("aucune borne Java ne dépasse la précision NUMERIC(8,2) des colonnes")
    void noLimitAboveColumnPrecision() {
        BigDecimal column = new BigDecimal("999999.99");
        for (TransportMode mode : TransportMode.values()) {
            assertThat(maxRequestWeight(mode)).isLessThanOrEqualTo(column);
            assertThat(maxTripCapacity(mode)).isLessThanOrEqualTo(column);
            assertThat(maxRecurrenceCapacity(mode)).isLessThanOrEqualTo(column);
        }
    }

    @Test
    @DisplayName("assertRequestWeight : 32 accepté, 32,5 refusé en 422 weight-too-heavy avec le plafond")
    void assertRequestWeight_bounds() {
        assertThatCode(() -> WeightLimits.assertRequestWeight(new BigDecimal("32"), PLANE)).doesNotThrowAnyException();
        assertThatCode(() -> WeightLimits.assertRequestWeight(new BigDecimal("999999.99"), CAR)).doesNotThrowAnyException();
        assertThatCode(() -> WeightLimits.assertRequestWeight(null, PLANE)).doesNotThrowAnyException();

        assertThatThrownBy(() -> WeightLimits.assertRequestWeight(new BigDecimal("32.5"), null))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("weight-too-heavy");
                    assertThat(ex.getMessage()).contains("32 kg").doesNotContain("32.00");
                });
        assertThatThrownBy(() -> WeightLimits.assertRequestWeight(new BigDecimal("1000000"), BOAT))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("weight-too-heavy"));
    }

    @Test
    @DisplayName("assertTripCapacity : 999,99 accepté en avion, 1 000 refusé en 422 trip-capacity-too-high")
    void assertTripCapacity_bounds() {
        assertThatCode(() -> WeightLimits.assertTripCapacity(new BigDecimal("999.99"), PLANE)).doesNotThrowAnyException();
        assertThatCode(() -> WeightLimits.assertTripCapacity(new BigDecimal("2500"), CAR)).doesNotThrowAnyException();
        assertThatCode(() -> WeightLimits.assertTripCapacity(null, PLANE)).doesNotThrowAnyException();

        assertThatThrownBy(() -> WeightLimits.assertTripCapacity(new BigDecimal("1000"), PLANE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("trip-capacity-too-high");
                    assertThat(ex.getMessage()).contains("999.99");
                });
        assertThatThrownBy(() -> WeightLimits.assertTripCapacity(new BigDecimal("1000000"), BUS))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("trip-capacity-too-high"));
    }

    @Test
    @DisplayName("assertRecurrenceCapacity : 40 kg accepté en avion, 40,5 refusé ; 1 200 accepté en route")
    void assertRecurrenceCapacity_bounds() {
        assertThatCode(() -> WeightLimits.assertRecurrenceCapacity(new BigDecimal("40"), PLANE)).doesNotThrowAnyException();
        assertThatCode(() -> WeightLimits.assertRecurrenceCapacity(new BigDecimal("1200"), CAR)).doesNotThrowAnyException();
        assertThatCode(() -> WeightLimits.assertRecurrenceCapacity(null, PLANE)).doesNotThrowAnyException();

        assertThatThrownBy(() -> WeightLimits.assertRecurrenceCapacity(new BigDecimal("40.5"), PLANE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("trip-capacity-too-high");
                    assertThat(ex.getMessage()).contains("40");
                });
    }
}
