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
    void roundTrip_keepsOffersWithinThreeDaysOfBothDates() {
        List<TravelpayoutsPrice> month = List.of(
                offer("2026-12-20T23:00:00+00:00", "2027-01-02T10:00:00+01:00", "500"), // -3 / 0 : gardée
                offer("2026-12-19T23:00:00+00:00", "2027-01-02T10:00:00+01:00", "100"), // -4 : écartée
                offer("2026-12-23T08:00:00+00:00", "2027-01-06T10:00:00+01:00", "100"), // retour +4 : écartée
                offer("2026-12-24T08:00:00+00:00", "2027-01-01T10:00:00+01:00", "700"), // +1 / -1 : écart 2
                offer("2026-12-22T08:00:00+00:00", "2027-01-03T10:00:00+01:00", "650"), // -1 / +1 : écart 2, moins chère
                offer("2026-12-23T08:00:00+00:00", "2027-01-05T10:00:00+01:00", "900")); // 0 / +3 : écart 3

        List<TravelpayoutsPrice> kept = FlightNearbyDates.filter(ROUND_TRIP, month, 3);

        assertThat(kept).extracting(p -> p.price().toPlainString())
                .containsExactlyInAnyOrder("650", "700", "500", "900");
        assertThat(FlightNearbyDates.filter(ROUND_TRIP, month, 4)).hasSize(6);
    }

    @Test
    void returnWindow_crossesYearEnd() {
        FlightQuery q = FlightQuery.of("PAR", "ABJ", "2026-12-30", "2027-01-02", false, "EUR", CLOCK);
        List<TravelpayoutsPrice> kept = FlightNearbyDates.filter(q, List.of(
                offer("2027-01-02T10:00:00+01:00", "2027-01-04T10:00:00+00:00", "400"), // départ +3
                offer("2027-01-03T10:00:00+01:00", "2027-01-04T10:00:00+00:00", "300"), // départ +4
                offer("2026-12-28T10:00:00+01:00", "2026-12-31T10:00:00+00:00", "350")), 3); // -2 / -2

        assertThat(kept).extracting(p -> p.price().toPlainString()).containsExactlyInAnyOrder("350", "400");
    }

    @Test
    void comparesLocalDatesAsWritten_notUtc() {
        // 26/12 à 23 h 30 heure locale (+01:00) = 26/12 22 h 30 UTC ; à 01 h 30 (+02:00) le 20/12 = 19/12 UTC.
        FlightQuery oneWay = FlightQuery.of("PAR", "ABJ", "2026-12-23", null, false, "EUR", CLOCK);
        List<TravelpayoutsPrice> kept = FlightNearbyDates.filter(oneWay, List.of(
                offer("2026-12-26T23:30:00+01:00", null, "300"),
                offer("2026-12-20T01:30:00+02:00", null, "310"),
                offer("2026-12-27T00:30:00+05:00", null, "200")), 3); // 26/12 UTC mais le 27 sur place

        assertThat(kept).extracting(p -> p.price().toPlainString()).containsExactly("300", "310");
    }

    @Test
    void oneWay_ignoresReturn_andRoundTripRejectsMissingOrUnreadableDates() {
        FlightQuery oneWay = FlightQuery.of("PAR", "ABJ", "2026-12-23", null, false, "EUR", CLOCK);
        assertThat(FlightNearbyDates.filter(oneWay, List.of(offer("2026-12-24T10:00:00+01:00", null, "300")), 3))
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
        assertThat(FlightNearbyDates.filter(ROUND_TRIP, rows, 3))
                .extracting(p -> p.price().toPlainString()).containsExactly("140");
    }

    @Test
    void monthRequested_isNotFiltered() {
        FlightQuery mixed = FlightQuery.of("PAR", "ABJ", "2026-12", "2027-01-02", false, "EUR", CLOCK);
        List<TravelpayoutsPrice> kept = FlightNearbyDates.filter(mixed, List.of(
                offer("2026-12-01T10:00:00+01:00", "2027-01-02T10:00:00+01:00", "300"),
                offer("2026-12-31T10:00:00+01:00", "2027-01-09T10:00:00+01:00", "200"),
                offer("2026-12-15T10:00:00+01:00", "2027-01-01T10:00:00+01:00", "100")), 3);

        assertThat(kept).extracting(p -> p.price().toPlainString()).containsExactly("300", "100");
    }

    private static List<String> months(FlightQuery q, int window, LocalDate today) {
        return FlightNearbyDates.monthQueries(q, window, today).stream()
                .map(m -> m.departure() + "/" + m.returnDate()).toList();
    }

    /** Le cas signalé : départ 29/11, retour 27/12, les départs du 1er et du 2 décembre comptent. */
    @Test
    void monthQueries_departureWindowCrossesIntoTheReturnMonth() {
        FlightQuery q = FlightQuery.of("PAR", "ABJ", "2026-11-29", "2026-12-27", true, "XOF", CLOCK);

        assertThat(months(q, 3, LocalDate.of(2026, 10, 15))).containsExactly("2026-11/2026-12", "2026-12/2026-12");
        FlightQuery first = FlightNearbyDates.monthQueries(q, 3, LocalDate.of(2026, 10, 15)).get(0);
        assertThat(first.origin()).isEqualTo("PAR");
        assertThat(first.destination()).isEqualTo("ABJ");
        assertThat(first.directOnly()).isTrue();
        assertThat(first.currency()).isEqualTo("XOF");
    }

    @Test
    void monthQueries_bothWindowsCrossAMonth_returnNeverBeforeDeparture() {
        FlightQuery q = FlightQuery.of("PAR", "ABJ", "2026-11-30", "2026-12-01", false, "EUR", CLOCK);

        assertThat(months(q, 3, LocalDate.of(2026, 10, 15)))
                .containsExactly("2026-11/2026-11", "2026-11/2026-12", "2026-12/2026-12");
    }

    @Test
    void monthQueries_skipPastMonths_keepMonthsAndOneWay() {
        FlightQuery early = FlightQuery.of("PAR", "ABJ", "2026-11-01", null, false, "EUR", CLOCK);
        assertThat(months(early, 3, LocalDate.of(2026, 10, 15))).containsExactly("2026-10/null", "2026-11/null");
        assertThat(months(early, 3, LocalDate.of(2026, 11, 1))).containsExactly("2026-11/null");

        FlightQuery mixed = FlightQuery.of("PAR", "ABJ", "2026-12", "2027-01-02", false, "EUR", CLOCK);
        assertThat(months(mixed, 7, LocalDate.of(2026, 10, 15))).containsExactly("2026-12/2026-12", "2026-12/2027-01");

        FlightQuery noDeparture = new FlightQuery("PAR", "ABJ", null,
                FlightDate.ofDay(LocalDate.of(2027, 1, 2)), false, "EUR");
        assertThat(months(noDeparture, 3, LocalDate.of(2026, 10, 15))).containsExactly("null/2026-12", "null/2027-01");
    }

    @Test
    void localDate_readsTheFirstTenCharacters() {
        assertThat(FlightNearbyDates.localDate("2026-12-23T23:59:00-05:00")).isEqualTo(LocalDate.of(2026, 12, 23));
        assertThat(FlightNearbyDates.localDate("2026-12-23")).isEqualTo(LocalDate.of(2026, 12, 23));
        assertThat(FlightNearbyDates.localDate(null)).isNull();
        assertThat(FlightNearbyDates.localDate("bad")).isNull();
    }
}
