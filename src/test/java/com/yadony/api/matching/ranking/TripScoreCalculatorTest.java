package com.yadony.api.matching.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.yadony.api.matching.ranking.TripRankingProperties.Weights;
import com.yadony.api.matching.ranking.TripScoreCalculator.TravelerQuality;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class TripScoreCalculatorTest {

    private static TripScoreCalculator calc(Weights w) {
        return new TripScoreCalculator(new TripRankingProperties(w, 7, 20, 1000, 300));
    }

    private final TripScoreCalculator calculator = calc(new Weights(30, 25, 20, 15, 10));

    private static TravelerQuality q(int cancel, int noShow, int deliveries, String avg, int n) {
        return new TravelerQuality(cancel, noShow, deliveries, avg == null ? null : new BigDecimal(avg), n);
    }

    @Test
    void veteran_awa_scoresHigh() {
        // Simulation validée : ~95,0 (tolérance ±1)
        double s = calculator.score(q(0, 0, 45, "4.9", 60), true, 5, 2);
        assertThat(s).isCloseTo(95.0, within(1.0));
    }

    @Test
    void fiveStarOneReview_belowVeteran() {
        double awa = calculator.score(q(0, 0, 45, "4.9", 60), true, 5, 2);
        double fresh = calculator.score(q(0, 0, 1, "5.0", 1), true, 5, 2);
        assertThat(fresh).isLessThan(awa);
    }

    @Test
    void newcomer_noHistory_isNeutral() {
        // fiabilité 4/5 = 0,8 ; avis 0 -> (0+20)/5 = 4 -> 0,5 ; envois 0
        double s = calculator.score(q(0, 0, 0, null, 0), false, 10, 10);
        double expected = (30 * 0.8 + 25 * 0.5 + 0 + 15 * (0.5 * (2.0 / 3) + 0.5 * (2.0 / 3)) + 0) / 100 * 100;
        assertThat(s).isCloseTo(expected, within(1e-9));
    }

    @Test
    void threeCancellations_dropsBelowNewcomerOnReliability() {
        // d=0 : nouveau = 4/5 = 0,8 ; 3 annulations = 4/(5+12) ~ 0,235
        double newcomer = calculator.score(q(0, 0, 0, null, 0), false, 10, 10);
        double cancelled = calculator.score(q(3, 0, 0, null, 0), false, 10, 10);
        assertThat(cancelled).isLessThan(newcomer);
        assertThat(newcomer - cancelled).isCloseTo(30 * (0.8 - 4.0 / 17), within(1e-9));
    }

    @Test
    void cashOnly_losesExactlyOnlinePaymentWeight() {
        TravelerQuality t = q(0, 0, 10, "4.5", 8);
        double online = calculator.score(t, true, 7, 3);
        double cash = calculator.score(t, false, 7, 3);
        assertThat(online - cash).isCloseTo(10.0 / 100 * 100, within(1e-9));
    }

    @Test
    void weightsZeroSum_returnsZero() {
        double s = calc(new Weights(0, 0, 0, 0, 0)).score(q(0, 0, 5, "4.0", 3), true, 1, 1);
        assertThat(s).isZero();
    }

    @Test
    void pastDeparture_and_zeroAge_clampFreshnessToOne() {
        TripScoreCalculator onlyFresh = calc(new Weights(0, 0, 0, 1, 0));
        assertThat(onlyFresh.score(q(0, 0, 0, null, 0), false, -3, 0)).isCloseTo(100.0, within(1e-9));
        assertThat(onlyFresh.score(q(0, 0, 0, null, 0), false, 100, 100)).isZero();
    }

    @Test
    void neverOutsideBounds_withExtremeInputs() {
        double s = calculator.score(q(50, 50, 100000, "5.0", 100000), true, -5, 0);
        assertThat(s).isBetween(0.0, 100.0);
        double low = calculator.score(q(50, 50, 0, "1.0", 10), false, 100, 100);
        assertThat(low).isBetween(0.0, 100.0);
    }

    // ─── J'aime ───────────────────────────────────────────────────────────────

    private final TripScoreCalculator withLikes = calc(new Weights(30, 25, 20, 15, 10, 10));

    @Test
    void plus_de_likes_donne_un_meilleur_score_a_egalite() {
        TravelerQuality t = q(0, 0, 10, "4.5", 8);
        double zero = withLikes.score(t, true, 7, 3, 0);
        double five = withLikes.score(t, true, 7, 3, 5);
        double fifteen = withLikes.score(t, true, 7, 3, 15);
        assertThat(five).isGreaterThan(zero);
        assertThat(fifteen).isGreaterThan(five);
    }

    @Test
    void le_bonus_sature_a_20_likes() {
        TravelerQuality t = q(0, 0, 10, "4.5", 8);
        assertThat(withLikes.score(t, true, 7, 3, 20))
                .isCloseTo(withLikes.score(t, true, 7, 3, 500), within(1e-9));
        assertThat(withLikes.score(t, true, 7, 3, 19)).isLessThan(withLikes.score(t, true, 7, 3, 20));
    }

    @Test
    void poids_likes_a_zero_desactive_le_critere() {
        TripScoreCalculator off = calc(new Weights(30, 25, 20, 15, 10, 0));
        TravelerQuality t = q(0, 0, 10, "4.5", 8);
        assertThat(off.score(t, true, 7, 3, 500)).isCloseTo(off.score(t, true, 7, 3, 0), within(1e-9));
    }

    @Test
    void sans_like_l_ordre_relatif_est_celui_d_avant() {
        TripScoreCalculator before = calc(new Weights(30, 25, 20, 15, 10));
        TravelerQuality good = q(0, 0, 45, "4.9", 60);
        TravelerQuality poor = q(2, 1, 1, "3.5", 3);
        assertThat(before.score(good, true, 5, 2)).isGreaterThan(before.score(poor, true, 5, 2));
        assertThat(withLikes.score(good, true, 5, 2, 0)).isGreaterThan(withLikes.score(poor, true, 5, 2, 0));
        // L'ancienne signature délègue avec zéro j'aime.
        assertThat(withLikes.score(good, true, 5, 2)).isCloseTo(withLikes.score(good, true, 5, 2, 0), within(1e-9));
        // Même ordre qu'avec cinq poids.
        assertThat(Math.signum(withLikes.score(good, false, 5, 2, 0) - withLikes.score(poor, true, 9, 8, 0)))
                .isEqualTo(Math.signum(before.score(good, false, 5, 2) - before.score(poor, true, 9, 8)));
    }

    @Test
    void likes_negatifs_traites_comme_zero() {
        TravelerQuality t = q(0, 0, 10, "4.5", 8);
        assertThat(withLikes.score(t, true, 7, 3, -4)).isCloseTo(withLikes.score(t, true, 7, 3, 0), within(1e-9));
    }

    @Test
    void poids_likes_negatif_refuse() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Weights(30, 25, 20, 15, 10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
