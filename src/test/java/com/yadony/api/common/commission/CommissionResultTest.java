package com.yadony.api.common.commission;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommissionResult — taux enregistrable")
class CommissionResultTest {

    private static CommissionResult withRate(String effectiveRate) {
        return new CommissionResult(new BigDecimal("0.50"), new BigDecimal("1.00"), new BigDecimal("1.50"),
                new BigDecimal(effectiveRate), true, false);
    }

    @Test
    @DisplayName("taux effectif 200 % (minimum sur un petit net) : borné à 0,999999 pour le CHECK [0, 1[")
    void storableRate_capsAboveOne() {
        assertThat(withRate("2.000000").storableRate()).isEqualTo(new BigDecimal("0.999999"));
    }

    @Test
    @DisplayName("taux effectif ordinaire : inchangé, à 6 décimales")
    void storableRate_keepsOrdinaryRate() {
        assertThat(withRate("0.07").storableRate()).isEqualTo(new BigDecimal("0.070000"));
    }

    @Test
    @DisplayName("borne exacte et taux nul : gardés tels quels")
    void storableRate_edges() {
        assertThat(CommissionResult.storableRate(new BigDecimal("0.999999"))).isEqualTo(new BigDecimal("0.999999"));
        assertThat(CommissionResult.storableRate(new BigDecimal("1"))).isEqualTo(new BigDecimal("0.999999"));
        assertThat(CommissionResult.storableRate(BigDecimal.ZERO)).isEqualTo(new BigDecimal("0.000000"));
    }
}
