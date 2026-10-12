package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FlightAlertWindowTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 12);

    private static TravelpayoutsPrice offer(String departureAt, String returnAt) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", departureAt, returnAt, new BigDecimal("300"),
                "AT", "781", 0, null, 400, "Kiwi.com", "/search/X");
    }

    @Test
    void anyDate_isOneOpenQuery_andAcceptsEverything() {
        FlightAlertWindow w = new FlightAlertWindow(null, null, null, null);

        List<FlightQuery> queries = w.queries("PAR", "ABJ", false, "EUR", TODAY);

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

        assertThat(w.queries("PAR", "ABJ", true, "EUR", TODAY)).singleElement()
                .satisfies(q -> assertThat(q.departure()).isEqualTo(FlightDate.ofDay(d)));
        assertThat(w.accepts(offer("2026-12-12T08:00:00+01:00", null))).isTrue();
        assertThat(w.accepts(offer("2026-12-13T08:00:00+01:00", null))).isFalse();
    }

    @Test
    void periodAcrossTwoMonths_queriesEachMonth_andFiltersOffers() {
        FlightAlertWindow w = new FlightAlertWindow(LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 10), null, null);

        assertThat(w.queries("PAR", "ABJ", false, "EUR", TODAY)).extracting(q -> q.departure().providerValue())
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
        assertThat(w.queries("PAR", "ABJ", false, "EUR", TODAY)).hasSize(2)
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

    @Test
    void elapsedMonths_costNoCall_andStartIsClampedToToday() {
        FlightAlertWindow w = new FlightAlertWindow(LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 20), null, null);

        assertThat(w.queries("PAR", "ABJ", false, "EUR", TODAY)).extracting(q -> q.departure().providerValue())
                .containsExactly("2026-10");
        assertThat(w.from(TODAY).departureFrom()).isEqualTo(TODAY);
    }

    @Test
    void singleDayLeftAfterClamp_isAPreciseDayQuery() {
        FlightAlertWindow w = new FlightAlertWindow(LocalDate.of(2026, 10, 1), TODAY, null, null);

        assertThat(w.queries("PAR", "ABJ", false, "EUR", TODAY)).singleElement()
                .satisfies(q -> assertThat(q.departure()).isEqualTo(FlightDate.ofDay(TODAY)));
    }

    @Test
    void returnMonthEndingBeforeDepartureMonth_isNotQueried() {
        FlightAlertWindow w = new FlightAlertWindow(LocalDate.of(2027, 1, 20), LocalDate.of(2027, 2, 10),
                LocalDate.of(2027, 1, 25), LocalDate.of(2027, 2, 20));

        assertThat(w.queries("PAR", "ABJ", false, "EUR", TODAY))
                .extracting(q -> q.departure().providerValue() + ">" + q.returnDate().providerValue())
                .containsExactly("2027-01>2027-01", "2027-01>2027-02", "2027-02>2027-02");
    }

    @Test
    void thirtyOneDays_canSpanThreeMonths() {
        FlightAlertWindow w = new FlightAlertWindow(LocalDate.of(2027, 1, 31), LocalDate.of(2027, 3, 2), null, null);

        assertThat(w.queries("PAR", "ABJ", false, "EUR", TODAY)).hasSize(3);
    }

    @Test
    void from_leavesFutureAndExpiredPeriodsUntouched() {
        FlightAlertWindow future = new FlightAlertWindow(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 5), null, null);
        FlightAlertWindow expired = new FlightAlertWindow(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 5), null, null);

        assertThat(future.from(TODAY)).isEqualTo(future);
        assertThat(expired.from(TODAY)).isEqualTo(expired);
    }
}
