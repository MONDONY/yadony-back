package com.yadony.api.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BidNegotiationPricing — brut négocié vers net voyageur")
class BidNegotiationPricingTest {

    @Test
    @DisplayName("cas nominal : 45 € brut à 5 % donne 42,86 net et 2,14 de commission")
    void nominalSplit() {
        var split = BidNegotiationPricing.split(new BigDecimal("45.00"), new BigDecimal("0.05"));

        assertThat(split.netEur()).isEqualByComparingTo("42.86");
        assertThat(split.commissionEur()).isEqualByComparingTo("2.14");
        assertThat(split.grossEur()).isEqualByComparingTo("45.00");
    }

    @ParameterizedTest(name = "brut {0} à {1} : net + commission = brut, au centime")
    @CsvSource({
            "45.00, 0.05", "0.01, 0.05", "33.33, 0.05", "99.99, 0.12",
            "10.01, 0.12", "7.77, 0.033", "1000000.00, 0.05", "12.34, 0.0",
    })
    @DisplayName("l'invariant net + commission = brut tient toujours")
    void invariantHolds(String gross, String rate) {
        var split = BidNegotiationPricing.split(new BigDecimal(gross), new BigDecimal(rate));

        assertThat(split.netEur().add(split.commissionEur()))
                .describedAs("net + commission doit valoir exactement le brut")
                .isEqualByComparingTo(new BigDecimal(gross));
        assertThat(split.netEur().scale()).isEqualTo(2);
        assertThat(split.commissionEur().scale()).isEqualTo(2);
        assertThat(split.commissionEur()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("taux nul : tout le brut revient au voyageur")
    void zeroRateGivesEverythingToTraveler() {
        var split = BidNegotiationPricing.split(new BigDecimal("20.00"), BigDecimal.ZERO);

        assertThat(split.netEur()).isEqualByComparingTo("20.00");
        assertThat(split.commissionEur()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("un brut nul ou négatif est refusé")
    void rejectsNonPositiveGross() {
        assertThatThrownBy(() -> BidNegotiationPricing.split(BigDecimal.ZERO, new BigDecimal("0.05")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BidNegotiationPricing.split(new BigDecimal("-1"), new BigDecimal("0.05")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un taux négatif est refusé")
    void rejectsNegativeRate() {
        assertThatThrownBy(() -> BidNegotiationPricing.split(new BigDecimal("10"), new BigDecimal("-0.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
