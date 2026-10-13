package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.yadony.api.flights.FlightTestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;

class FlightNearbyDatesTest {

    private static final FlightQuery ROUND_TRIP =
            FlightQuery.of("ABJ", "PAR", "2026-12-23", "2027-01-02", false, "EUR", CLOCK);

    static TravelpayoutsPrice offer(String departureAt, String returnAt, String amount) {
        return new TravelpayoutsPrice("ABJ", "PAR", "ABJ", "CDG", departureAt, returnAt, new BigDecimal(amount),
                "SS", "123", 0, 0, 400, "Gate", "/search/X?t=" + departureAt + returnAt + amount);
    }

    @Test
    void roundTrip_keepsOffersWithinThreeDaysOfBothDates_closestFirstThenCheapest() {
        List<TravelpayoutsPrice> month = List.of(
                offer("2026-12-20T23:00:00+00:00", "2027-01-02T10:00:00+01:00", "500"), // -3 / 0 : gardée
                offer("2026-12-19T23:00:00+00:00", "2027-01-02T10:00:00+01:00", "100"), // -4 : écartée
                offer("2026-12-23T08:00:00+00:00", "2027-01-06T10:00:00+01:00", "100"), // retour +4 : écartée
                offer("2026-12-24T08:00:00+00:00", "2027-01-01T10:00:00+01:00", "700"), // +1 / -1 : écart 2
                offer("2026-12-22T08:00:00+00:00", "2027-01-03T10:00:00+01:00", "650"), // -1 / +1 : écart 2, moins chère
                offer("2026-12-23T08:00:00+00:00", "2027-01-05T10:00:00+01:00", "900")); // 0 / +3 : écart 3

        List<TravelpayoutsPrice> kept = FlightNearbyDates.filter(ROUND_TRIP, month, 150);

        assertThat(kept).extracting(p -> p.price().toPlainString())
                .containsExactly("650", "700", "500", "900");
    }

    @Test
    void returnWindow_crossesYearEnd() {
        FlightQuery q = FlightQuery.of("PAR", "ABJ", "2026-12-30", "2027-01-02", false, "EUR", CLOCK);
        List<TravelpayoutsPrice> kept = FlightNearbyDates.filter(q, List.of(
                offer("2027-01-02T10:00:00+01:00", "2027-01-04T10:00:00+00:00", "400"), // départ +3
                offer("2027-01-03T10:00:00+01:00", "2027-01-04T10:00:00+00:00", "300"), // départ +4
                offer("2026-12-28T10:00:00+01:00", "2026-12-31T10:00:00+00:00", "350")), 150); // -2 / -2

        assertThat(kept).extracting(p -> p.price().toPlainString()).containsExactly("350", "400");
    }

    @Test
    void comparesLocalDatesAsWritten_notUtc() {
        // 26/12 à 23 h 30 heure locale (+01:00) = 26/12 22 h 30 UTC ; à 01 h 30 (+02:00) le 20/12 = 19/12 UTC.
        FlightQuery oneWay = FlightQuery.of("PAR", "ABJ", "2026-12-23", null, false, "EUR", CLOCK);
        List<TravelpayoutsPrice> kept = FlightNearbyDates.filter(oneWay, List.of(
                offer("2026-12-26T23:30:00+01:00", null, "300"),
                offer("2026-12-20T01:30:00+02:00", null, "310"),
                offer("2026-12-27T00:30:00+05:00", null, "200")), 150); // 26/12 UTC mais le 27 sur place

        assertThat(kept).extracting(p -> p.price().toPlainString()).containsExactly("300", "310");
    }

    @Test
    void oneWay_ignoresReturn_andRoundTripRejectsMissingOrUnreadableDates() {
        FlightQuery oneWay = FlightQuery.of("PAR", "ABJ", "2026-12-23", null, false, "EUR", CLOCK);
        assertThat(FlightNearbyDates.filter(oneWay, List.of(offer("2026-12-24T10:00:00+01:00", null, "300")), 150))
                .hasSize(1);

        List<TravelpayoutsPrice> rows = new ArrayList<>(Arrays.asList(
                offer("2026-12-23T10:00:00+01:00", null, "100"),          // retour absent
                offer(null, "2027-01-02T10:00:00+01:00", "110"),          // départ absent
                offer("2026-12-2", "2027-01-02T10:00:00+01:00", "120"),   // trop court
                offer("2026-13-23T10:00", "2027-01-02T10:00:00+01:00", "130"), // illisible
                new TravelpayoutsPrice("ABJ", "PAR", null, null, "2026-12-23T10:00:00+01:00",
                        "2027-01-02T10:00:00+01:00", null, "SS", null, null, null, null, null, null),
                null,
                offer("2026-12-23T10:00:00+01:00", "2027-01-02T10:00:00+01:00", "140")));
        assertThat(FlightNearbyDates.filter(ROUND_TRIP, rows, 150))
                .extracting(p -> p.price().toPlainString()).containsExactly("140");
    }

    @Test
    void monthRequested_isNotFiltered_andLimitApplies() {
        FlightQuery mixed = FlightQuery.of("PAR", "ABJ", "2026-12", "2027-01-02", false, "EUR", CLOCK);
        List<TravelpayoutsPrice> kept = FlightNearbyDates.filter(mixed, List.of(
                offer("2026-12-01T10:00:00+01:00", "2027-01-02T10:00:00+01:00", "300"),
                offer("2026-12-31T10:00:00+01:00", "2027-01-03T10:00:00+01:00", "200"),
                offer("2026-12-15T10:00:00+01:00", "2027-01-01T10:00:00+01:00", "100")), 2);

        assertThat(kept).extracting(p -> p.price().toPlainString()).containsExactly("300", "100");
    }

    @Test
    void localDate_readsTheFirstTenCharacters() {
        assertThat(FlightNearbyDates.localDate("2026-12-23T23:59:00-05:00")).isEqualTo(LocalDate.of(2026, 12, 23));
        assertThat(FlightNearbyDates.localDate("2026-12-23")).isEqualTo(LocalDate.of(2026, 12, 23));
        assertThat(FlightNearbyDates.localDate(null)).isNull();
        assertThat(FlightNearbyDates.localDate("bad")).isNull();
    }
}
