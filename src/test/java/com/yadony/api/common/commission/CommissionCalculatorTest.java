package com.yadony.api.common.commission;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.currency.SupportedCurrency;
import com.yadony.api.promo.PromoDiscount;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommissionCalculator — grille marginale, réductions, minimum, inverse")
class CommissionCalculatorTest {

    /** Grille 7/6/5 et minimums par défaut du chantier. */
    private static final CommissionGrid GRID = new CommissionGrid(
            UUID.randomUUID(), LocalDateTime.of(2026, 11, 1, 0, 0),
            List.of(tier("0", "0.07"), tier("100", "0.06"), tier("300", "0.05")),
            Map.of(SupportedCurrency.EUR, new BigDecimal("1.00"),
                    SupportedCurrency.XOF, new BigDecimal("650"),
                    SupportedCurrency.XAF, new BigDecimal("650"),
                    SupportedCurrency.USD, new BigDecimal("1.10"),
                    SupportedCurrency.CAD, new BigDecimal("1.50"),
                    SupportedCurrency.GBP, new BigDecimal("0.90"),
                    SupportedCurrency.CHF, new BigDecimal("0.95")));

    private static final CommissionReductions NEGOTIATION =
            new CommissionReductions(new BigDecimal("0.04"), null, new BigDecimal("0.5"));

    @Test
    void compute_isMonotonicAcrossTierBounds() {
        BigDecimal step = new BigDecimal("0.01");
        BigDecimal previous = BigDecimal.ZERO;
        for (BigDecimal net = step; net.compareTo(new BigDecimal("600.00")) <= 0; net = net.add(step)) {
            BigDecimal gross = CommissionCalculator.compute(net, SupportedCurrency.EUR, GRID,
                    CommissionReductions.NONE).gross();
            assertThat(gross).as("brut pour un net de %s", net).isGreaterThan(previous);
            previous = gross;
        }
    }

    @Test
    void compute_roundsOnceAtEnd_xof() {
        // 65 595,7 × 0,07 + 34 404,3 × 0,06 = 6 655,957 : un arrondi par tranche donnerait 6 656
        // aussi, mais des bornes arrondies (65 596) donneraient 6 655,98. Un seul arrondi, à la fin.
        CommissionResult result = CommissionCalculator.compute(new BigDecimal("100000"),
                SupportedCurrency.XOF, GRID, CommissionReductions.NONE);
        assertThat(result.commission()).isEqualByComparingTo("6656");
        assertThat(result.commission().scale()).isZero();
        assertThat(result.gross()).isEqualByComparingTo("106656");
    }

    @Test
    void compute_zeroRateAfterPromo_isZeroNoMinimum() {
        CommissionResult result = CommissionCalculator.compute(new BigDecimal("20.00"), SupportedCurrency.EUR,
                GRID, new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.10")), null));
        assertThat(result.commission()).isEqualByComparingTo("0.00");
        assertThat(result.gross()).isEqualByComparingTo("20.00");
        assertThat(result.minimumApplied()).isFalse();
        assertThat(result.promoEffective()).isTrue();
        assertThat(result.effectiveRate()).isEqualByComparingTo("0");
    }

    @Test
    void compute_overrideZero_isZero() {
        CommissionResult result = CommissionCalculator.compute(new BigDecimal("5.00"), SupportedCurrency.EUR,
                GRID, new CommissionReductions(BigDecimal.ZERO, null, null));
        assertThat(result.commission()).isEqualByComparingTo("0.00");
        assertThat(result.minimumApplied()).isFalse();
    }

    @Test
    void compute_voucherAfterMinimum() {
        CommissionResult result = CommissionCalculator.compute(new BigDecimal("5.00"), SupportedCurrency.EUR,
                GRID, new CommissionReductions(null, null, new BigDecimal("0.5")));
        assertThat(result.commission()).isEqualByComparingTo("0.50");
        assertThat(result.gross()).isEqualByComparingTo("5.50");
        assertThat(result.minimumApplied()).isTrue();
    }

    @Test
    void compute_overrideAboveGrid_keepsGrid() {
        // Le taux spécial ne peut que réduire : min(grille, net × taux spécial).
        CommissionResult result = CommissionCalculator.compute(new BigDecimal("100.00"), SupportedCurrency.EUR,
                GRID, new CommissionReductions(new BigDecimal("0.12"), null, null));
        assertThat(result.commission()).isEqualByComparingTo("7.00");
    }

    @Test
    void compute_effectiveRate_hasSixDecimals() {
        CommissionResult result = CommissionCalculator.compute(new BigDecimal("150.00"), SupportedCurrency.EUR,
                GRID, CommissionReductions.NONE);
        // 10,00 / 150,00 = 0,0666…
        assertThat(result.effectiveRate()).isEqualByComparingTo("0.066667");
        assertThat(result.effectiveRate().scale()).isEqualTo(6);
        assertThat(result.net()).isEqualByComparingTo("150.00");
    }

    @Test
    void nullReductions_meanNone() {
        assertThat(CommissionCalculator.compute(new BigDecimal("20.00"), SupportedCurrency.EUR, GRID, null)
                .commission()).isEqualByComparingTo("1.40");
        assertThat(CommissionCalculator.netForGross(new BigDecimal("21.40"), SupportedCurrency.EUR, GRID, null))
                .isEqualByComparingTo("20.00");
    }

    @Test
    void compute_rejectsNonPositiveNet() {
        assertThatThrownBy(() -> CommissionCalculator.compute(BigDecimal.ZERO, SupportedCurrency.EUR,
                GRID, CommissionReductions.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minimum_missingCurrency_throwsIllegalState() {
        CommissionGrid euroOnly = new CommissionGrid(UUID.randomUUID(), LocalDateTime.of(2026, 11, 1, 0, 0),
                List.of(tier("0", "0.07")), Map.of(SupportedCurrency.EUR, new BigDecimal("1.00")));
        assertThatThrownBy(() -> euroOnly.minimum(SupportedCurrency.XOF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("XOF");
    }

    @Test
    void gridCommission_isUnroundedAndConvertsBoundsWithoutRounding() {
        assertThat(GRID.gridCommission(new BigDecimal("100000"), SupportedCurrency.XOF))
                .isEqualByComparingTo("6655.957");
        assertThat(GRID.gridCommission(new BigDecimal("14.28"), SupportedCurrency.EUR))
                .isEqualByComparingTo("0.9996");
    }

    @ParameterizedTest
    @EnumSource(SupportedCurrency.class)
    void netForGross_inverseOfCompute_roundTrip(SupportedCurrency currency) {
        assertRoundTrip(currency, CommissionReductions.NONE);
    }

    @ParameterizedTest
    @EnumSource(SupportedCurrency.class)
    void netForGross_inverseOfCompute_roundTrip_withOverrideAndVoucher(SupportedCurrency currency) {
        assertRoundTrip(currency, NEGOTIATION);
    }

    @Test
    void netForGross_betweenTwoGrossValues_returnsLargestAffordableNet() {
        // Net 20,07 € → commission 1,4049 → 1,40, brut 21,47 € ; net 20,08 € → 1,4056 → 1,41,
        // brut 21,49 €. Aucun net ne coûte 21,48 € : on rend le plus grand net payable, 20,07 €.
        BigDecimal net = CommissionCalculator.netForGross(new BigDecimal("21.48"), SupportedCurrency.EUR,
                GRID, CommissionReductions.NONE);
        assertThat(net).isEqualByComparingTo("20.07");
        assertThat(net.scale()).isEqualTo(2);
    }

    @Test
    void netForGross_overrideZero_netEqualsGross() {
        BigDecimal net = CommissionCalculator.netForGross(new BigDecimal("42.00"), SupportedCurrency.EUR,
                GRID, new CommissionReductions(BigDecimal.ZERO, null, null));
        assertThat(net).isEqualByComparingTo("42.00");
    }

    @Test
    void netForGross_atOrBelowMinimum_throws() {
        assertThatThrownBy(() -> CommissionCalculator.netForGross(new BigDecimal("1.00"), SupportedCurrency.EUR,
                GRID, CommissionReductions.NONE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getStatus().value()).isEqualTo(422);
                    assertThat(ex.getErrorCode()).isEqualTo("budget-below-commission-minimum");
                });
        assertThat(CommissionCalculator.netForGross(new BigDecimal("1.01"), SupportedCurrency.EUR,
                GRID, CommissionReductions.NONE)).isEqualByComparingTo("0.01");
    }

    @Test
    void netForGross_rejectsPromo() {
        // L'inverse ne porte que sur la grille, le taux spécial, le bon et le minimum (spec §4.3) :
        // un code promo peut rendre N ↦ N + C(N) décroissante (promo qui absorbe tout au-delà d'un seuil).
        CommissionReductions withPromo =
                new CommissionReductions(null, PromoDiscount.points(new BigDecimal("0.05")), null);
        assertThatThrownBy(() -> CommissionCalculator.netForGross(new BigDecimal("50.00"), SupportedCurrency.EUR,
                GRID, withPromo))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertRoundTrip(SupportedCurrency currency, CommissionReductions reductions) {
        Random random = new Random(20261018L + currency.ordinal());
        // Nets jusqu'à 2 000 € convertis : couvre la zone du minimum et les trois tranches.
        long maxMinor = new BigDecimal("2000").multiply(currency.unitsPerEur())
                .movePointRight(currency.minorUnit()).longValue();
        for (int i = 0; i < 2000; i++) {
            long minor = 1 + (long) (random.nextDouble() * maxMinor);
            BigDecimal net = BigDecimal.valueOf(minor, currency.minorUnit());
            BigDecimal gross = CommissionCalculator.compute(net, currency, GRID, reductions).gross();
            assertThat(CommissionCalculator.netForGross(gross, currency, GRID, reductions))
                    .as("%s : net %s, brut %s", currency, net, gross)
                    .isEqualByComparingTo(net.setScale(currency.minorUnit(), RoundingMode.UNNECESSARY));
        }
    }

    private static CommissionTier tier(String fromEur, String rate) {
        return new CommissionTier(new BigDecimal(fromEur), new BigDecimal(rate));
    }
}
