package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlightAlertWindowTest {

    private static TravelpayoutsPrice offer(String departureAt, String returnAt) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", departureAt, returnAt, new BigDecimal("300"),
                "AT", "781", 0, null, 400, "Kiwi.com", "/search/X");
    }

    @Test
    void anyDate_isOneOpenQuery_andAcceptsEverything() {
        FlightAlertWindow w = new FlightAlertWindow(null, null, null, null);

        List<FlightQuery> queries = w.queries("PAR", "ABJ", false, "EUR");

        assertThat(queries).singleElement().satisfies(q -> {
            assertThat(q.departure()).isNull();
            assertThat(q.returnDate()).isNull();
        });
        assertThat(w.accepts(offer("2027-05-01T10:00:00+01:00", null))).isTrue();
        assertThat(w.roundTrip()).isFalse();
    }

    @Test
    void singleDay_isAPreciseDayQuery() {
        LocalDate d = LocalDate.of(2026, 12, 12);
        FlightAlertWindow w = new FlightAlertWindow(d, d, null, null);

        assertThat(w.queries("PAR", "ABJ", true, "EUR")).singleElement()
                .satisfies(q -> assertThat(q.departure()).isEqualTo(FlightDate.ofDay(d)));
        assertThat(w.accepts(offer("2026-12-12T08:00:00+01:00", null))).isTrue();
        assertThat(w.accepts(offer("2026-12-13T08:00:00+01:00", null))).isFalse();
    }

    @Test
    void periodAcrossTwoMonths_queriesEachMonth_andFiltersOffers() {
        FlightAlertWindow w = new FlightAlertWindow(LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 10), null, null);

        assertThat(w.queries("PAR", "ABJ", false, "EUR")).extracting(q -> q.departure().providerValue())
                .containsExactly("2026-12", "2027-01");
        assertThat(w.accepts(offer("2026-12-19T23:59:00+01:00", null))).isFalse();
        assertThat(w.accepts(offer("2026-12-20T00:00:00+01:00", null))).isTrue();
        assertThat(w.accepts(offer("2027-01-10T12:00:00+01:00", null))).isTrue();
        assertThat(w.accepts(offer("2027-01-11T12:00:00+01:00", null))).isFalse();
        assertThat(w.accepts(offer(null, null))).isFalse();
    }

    @Test
    void roundTrip_isCartesianProduct_andFiltersTheReturn() {
        FlightAlertWindow w = new FlightAlertWindow(LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 10),
                LocalDate.of(2027, 1, 20), LocalDate.of(2027, 1, 31));

        assertThat(w.roundTrip()).isTrue();
        assertThat(w.queries("PAR", "ABJ", false, "EUR")).hasSize(2)
                .allSatisfy(q -> assertThat(q.returnDate().providerValue()).isEqualTo("2027-01"));
        assertThat(w.accepts(offer("2026-12-25T10:00:00+01:00", "2027-01-25T10:00:00+01:00"))).isTrue();
        assertThat(w.accepts(offer("2026-12-25T10:00:00+01:00", "2027-02-02T10:00:00+01:00"))).isFalse();
        assertThat(w.accepts(offer("2026-12-25T10:00:00+01:00", null))).isFalse();
    }

    @Test
    void key_distinguishesPeriods() {
        LocalDate a = LocalDate.of(2026, 12, 1);
        assertThat(new FlightAlertWindow(a, a.plusDays(5), null, null).key())
                .isNotEqualTo(new FlightAlertWindow(a, a.plusDays(6), null, null).key())
                .isEqualTo(new FlightAlertWindow(a, a.plusDays(5), null, null).key());
    }

    @Test
    void dayOf_ignoresGarbage() {
        assertThat(FlightAlertWindow.dayOf("2026-12-04T11:30:00+01:00")).isEqualTo(LocalDate.of(2026, 12, 4));
        assertThat(FlightAlertWindow.dayOf("nope")).isNull();
        assertThat(FlightAlertWindow.dayOf(null)).isNull();
    }
}
