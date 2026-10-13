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

    // ── grossForNet (FLUTTER-NS) ──────────────────────────────────────────────

    @ParameterizedTest(name = "net {0} à {1} en euros : le brut rend exactement ce net")
    @CsvSource({
            "35.00, 0.12", "10.01, 0.12", "10.05, 0.12", "0.01, 0.12", "42.86, 0.05",
            "999.99, 0.033", "12.34, 0.0", "100.00, 0.15",
    })
    void grossForNet_eur_roundTripsExactly(String net, String rate) {
        BigDecimal gross = BidNegotiationPricing.grossForNet(new BigDecimal(net), new BigDecimal(rate), 2);
        assertThat(BidNegotiationPricing.split(gross, new BigDecimal(rate)).netEur()).isEqualByComparingTo(net);
        // Le plus petit : un centime de moins ne rend plus ce net.
        BigDecimal below = gross.subtract(new BigDecimal("0.01"));
        if (below.signum() > 0) {
            assertThat(BidNegotiationPricing.split(below, new BigDecimal(rate)).netEur())
                    .isLessThan(new BigDecimal(net));
        }
    }

    @Test
    @DisplayName("10,01 € net à 12 % : 11,21 € brut, et non 11,22 (arrondi supérieur brut)")
    void grossForNet_stepsDownWhenRoundingAlreadyReachesTheNet() {
        assertThat(BidNegotiationPricing.grossForNet(new BigDecimal("10.01"), new BigDecimal("0.12"), 2))
                .isEqualByComparingTo("11.21");
    }

    @ParameterizedTest(name = "net {0} F CFA à 12 % : brut entier {1}")
    @CsvSource({ "25000, 28000", "25001, 28002", "1, 2", "5000, 5600" })
    void grossForNet_xof_wholeFrancs_netAtLeastRequested(String net, String expectedGross) {
        BigDecimal gross = BidNegotiationPricing.grossForNet(new BigDecimal(net), new BigDecimal("0.12"), 0);
        assertThat(gross).isEqualByComparingTo(expectedGross);
        assertThat(gross.scale()).isZero();
        assertThat(BidNegotiationPricing.split(gross, new BigDecimal("0.12")).netEur())
                .isGreaterThanOrEqualTo(new BigDecimal(net));
    }

    @Test
    void grossForNet_rejectsInvalidInput() {
        assertThatThrownBy(() -> BidNegotiationPricing.grossForNet(BigDecimal.ZERO, new BigDecimal("0.12"), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BidNegotiationPricing.grossForNet(BigDecimal.TEN, new BigDecimal("-0.1"), 2))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
