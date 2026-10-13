package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.yadony.api.flights.FlightTestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;

class FlightSimilarStaysTest {

    /** 15/10/2026, jour des tests. */
    private static final LocalDate TODAY = LocalDate.now(CLOCK);
    private static final FlightQuery HOLIDAYS =
            FlightQuery.of("PAR", "ABJ", "2026-11-29", "2026-12-27", false, "EUR", CLOCK);

    private static TravelpayoutsPrice trip(String out, String back, String amount, int transfers) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", out + "T11:30:00+01:00",
                back == null ? null : back + "T09:00:00+00:00", new BigDecimal(amount), "AF", "702",
                transfers, transfers, 900, "Gate", "/search/" + out + back + amount);
    }

    @Test
    void applies_onlyToRoundTripsOnExactDays() {
        assertThat(FlightSimilarStays.applies(HOLIDAYS)).isTrue();
        assertThat(FlightSimilarStays.applies(FlightQuery.of("PAR", "ABJ", "2026-11-29", null, false, "EUR", CLOCK)))
                .isFalse();
        assertThat(FlightSimilarStays.applies(FlightQuery.of("PAR", "ABJ", "2026-11", "2026-12", false, "EUR", CLOCK)))
                .isFalse();
        assertThat(FlightSimilarStays.applies(FlightQuery.of("PAR", "ABJ", "2026-11-29", "2026-12", false, "EUR", CLOCK)))
                .isFalse();
        assertThat(FlightSimilarStays.applies(FlightQuery.of("PAR", "ABJ", null, null, false, "EUR", CLOCK))).isFalse();
    }

    @Test
    void stayRange_isTheRequestedStayPlusOrMinusAWeek_neverNegative() {
        assertThat(FlightSimilarStays.requestedStay(HOLIDAYS)).isEqualTo(28);
        assertThat(FlightSimilarStays.stayRange(HOLIDAYS)).isEqualTo(new FlightSimilarStays.StayRange(21, 35));
        FlightQuery shortStay = FlightQuery.of("PAR", "ABJ", "2026-11-29", "2026-12-02", false, "EUR", CLOCK);
        assertThat(FlightSimilarStays.stayRange(shortStay)).isEqualTo(new FlightSimilarStays.StayRange(0, 10));
    }

    @Test
    void departureMonths_previousOnlyWhileNotOver() {
        assertThat(FlightSimilarStays.departureMonths(HOLIDAYS, TODAY))
                .containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11), YearMonth.of(2026, 12));
        FlightQuery october = FlightQuery.of("PAR", "ABJ", "2026-10-20", "2026-11-10", false, "EUR", CLOCK);
        assertThat(FlightSimilarStays.departureMonths(october, TODAY))
                .containsExactly(YearMonth.of(2026, 10), YearMonth.of(2026, 11));
    }

    @Test
    void monthQueries_mostPromisingPairsFirst_withoutImpossiblePairs() {
        List<FlightQuery> queries = FlightSimilarStays.monthQueries(HOLIDAYS, TODAY);

        assertThat(queries).extracting(q -> q.departure() + "×" + q.returnDate())
                .startsWith("2026-11×2026-12")
                .contains("2026-10×2026-11", "2026-11×2026-11", "2026-12×2027-01", "2026-12×2027-02")
                .doesNotContain("2026-10×2026-10") // retour au plus tôt le 05/11 pour un départ le 15/10
                .doesNotHaveDuplicates();
        assertThat(queries).allSatisfy(q -> {
            assertThat(q.origin()).isEqualTo("PAR");
            assertThat(q.destination()).isEqualTo("ABJ");
            assertThat(q.currency()).isEqualTo("EUR");
            assertThat(q.directOnly()).isFalse();
            assertThat(q.departure().isDay()).isFalse();
        });
        // Rendement : 10×11 (17 jours de départ à venir) passe avant 11×11 (départs du 1er au 9).
        List<String> keys = queries.stream().map(q -> q.departure() + "×" + q.returnDate()).toList();
        assertThat(keys.indexOf("2026-10×2026-11")).isLessThan(keys.indexOf("2026-11×2026-11"));
    }

    @Test
    void possibleStays_countsOnlyDaysToCome() {
        var range = new FlightSimilarStays.StayRange(21, 35);
        assertThat(FlightSimilarStays.possibleStays(YearMonth.of(2026, 10), YearMonth.of(2026, 10), range, TODAY))
                .isZero();
        assertThat(FlightSimilarStays.possibleStays(YearMonth.of(2026, 11), YearMonth.of(2026, 11), range, TODAY))
                .isEqualTo(9 + 8 + 7 + 6 + 5 + 4 + 3 + 2 + 1);
    }

    @Test
    void select_keepsStaysInRange_inTheWindow_withoutExcluded_sortedAndCapped() {
        List<TravelpayoutsPrice> rows = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rows.add(trip("2026-11-" + String.format("%02d", 2 + i), "2026-12-" + String.format("%02d", 2 + i),
                    String.valueOf(900 - i * 10), 1)); // 30 j, prix décroissants
        }
        TravelpayoutsPrice alreadyShown = trip("2026-11-23", "2026-12-21", "100", 1);
        rows.add(alreadyShown);
        rows.add(trip("2026-11-10", "2026-12-26", "50", 1));   // 46 j
        rows.add(trip("2026-11-20", "2026-12-08", "50", 1));   // 18 j
        rows.add(trip("2026-10-10", "2026-11-05", "50", 1));   // départ passé
        rows.add(trip("2027-01-05", "2027-02-01", "50", 1));   // départ hors fenêtre
        rows.add(trip("2026-11-05", null, "50", 1));           // aller simple
        rows.add(null);

        List<TravelpayoutsPrice> kept = FlightSimilarStays.select(HOLIDAYS, rows, TODAY,
                Set.of(FlightPriceService.dedupKey(alreadyShown)));

        assertThat(kept).hasSize(FlightSimilarStays.MAX_OFFERS)
                .extracting(p -> p.price().toPlainString())
                .containsExactly("790", "800", "810", "820", "830", "840", "850", "860", "870", "880");
    }

    @Test
    void select_directOnlySearch_keepsOnlyDirects() {
        FlightQuery directs = FlightQuery.of("PAR", "ABJ", "2026-11-29", "2026-12-27", true, "EUR", CLOCK);
        List<TravelpayoutsPrice> kept = FlightSimilarStays.select(directs, List.of(
                trip("2026-11-15", "2026-12-10", "400", 1),
                trip("2026-11-16", "2026-12-12", "450", 0)), TODAY, Set.of());

        assertThat(kept).extracting(p -> p.price().toPlainString()).containsExactly("450");
    }
}
