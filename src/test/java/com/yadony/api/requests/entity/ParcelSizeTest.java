package com.yadony.api.requests.entity;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class ParcelSizeTest {

    @Test
    void small_le_5() {
        assertThat(ParcelSize.fromWeightKg(new BigDecimal("0.5"))).isEqualTo(ParcelSize.SMALL);
        assertThat(ParcelSize.fromWeightKg(new BigDecimal("5"))).isEqualTo(ParcelSize.SMALL);
    }

    @Test
    void medium_5_to_15() {
        assertThat(ParcelSize.fromWeightKg(new BigDecimal("5.01"))).isEqualTo(ParcelSize.MEDIUM);
        assertThat(ParcelSize.fromWeightKg(new BigDecimal("15"))).isEqualTo(ParcelSize.MEDIUM);
    }

    @Test
    void large_gt_15() {
        assertThat(ParcelSize.fromWeightKg(new BigDecimal("15.01"))).isEqualTo(ParcelSize.LARGE);
        assertThat(ParcelSize.fromWeightKg(new BigDecimal("32"))).isEqualTo(ParcelSize.LARGE);
    }

    /** Garde-fou (déjà vert avant V380) : une demande route en tonnes reste LARGE. */
    @Test
    void fromWeightKg_above32_isLarge() {
        assertThat(ParcelSize.fromWeightKg(new BigDecimal("1200"))).isEqualTo(ParcelSize.LARGE);
        assertThat(ParcelSize.fromWeightKg(new BigDecimal("999999.99"))).isEqualTo(ParcelSize.LARGE);
    }
}
