package com.yadony.api.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedDecimalsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "0", "1", "12.50", "-3.2", "500.00", "655957", "999999999999999",
            "0.000000000000000001", "1E+2", "1e14", "1.08"
    })
    void realisticValues_areBounded(String text) {
        assertThat(BoundedDecimals.isBounded(new BigDecimal(text))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "1e10000000", "1e999999999", "-1e16", "1000000000000000",
            "1e-10000000", "0.0000000000000000001"
    })
    void hugeExponentsOrPrecision_areRejected(String text) {
        assertThat(BoundedDecimals.isBounded(new BigDecimal(text))).isFalse();
    }

    @org.junit.jupiter.api.Test
    void null_isLeftToBeanValidation() {
        assertThat(BoundedDecimals.isBounded(null)).isTrue();
    }
}
