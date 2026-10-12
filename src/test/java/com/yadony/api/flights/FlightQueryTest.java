package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;

import static com.yadony.api.flights.FlightTestData.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlightQueryTest {

    @Test
    void normalizesCodesAndCurrency() {
        FlightQuery q = FlightQuery.of(" par ", "abj", "2026-12", null, true, "xof", CLOCK);

        assertThat(q.origin()).isEqualTo("PAR");
        assertThat(q.destination()).isEqualTo("ABJ");
        assertThat(q.departure()).isEqualTo(FlightDate.ofMonth(YearMonth.of(2026, 12)));
        assertThat(q.returnDate()).isNull();
        assertThat(q.roundTrip()).isFalse();
        assertThat(q.directOnly()).isTrue();
        assertThat(q.currency()).isEqualTo("XOF");
    }

    @Test
    void defaultsCurrencyToEuro_andAcceptsRoundTrip() {
        FlightQuery q = FlightQuery.of("PAR", "DKR", "2026-12", "2027-01", false, null, CLOCK);

        assertThat(q.currency()).isEqualTo("EUR");
        assertThat(q.roundTrip()).isTrue();
        assertThat(q.key()).isEqualTo("PAR-DKR|2026-12|2027-01|false|EUR");
    }

    @Test
    void currentMonthIsAccepted() {
        assertThat(FlightQuery.of("PAR", "ABJ", "2026-10", null, false, "EUR", CLOCK).departure())
                .isEqualTo(FlightDate.ofMonth(YearMonth.of(2026, 10)));
    }

    @Test
    void rejectsInvalidCodes() {
        assertCode(() -> FlightQuery.of("PARIS", "ABJ", null, null, false, null, CLOCK), "flights-invalid-code");
        assertCode(() -> FlightQuery.of("PAR", "A1J", null, null, false, null, CLOCK), "flights-invalid-code");
        assertCode(() -> FlightQuery.of(null, "ABJ", null, null, false, null, CLOCK), "flights-invalid-code");
    }

    @Test
    void rejectsSameAirport() {
        assertCode(() -> FlightQuery.of("PAR", "par", null, null, false, null, CLOCK), "flights-same-airport");
    }

    @Test
    void rejectsPastOrMalformedMonths() {
        assertCode(() -> FlightQuery.of("PAR", "ABJ", "2026-09", null, false, null, CLOCK), "flights-date-past");
        assertCode(() -> FlightQuery.of("PAR", "ABJ", "2026-10-14", null, false, null, CLOCK), "flights-date-past");
        assertCode(() -> FlightQuery.of("PAR", "ABJ", null, "2026-01", false, null, CLOCK), "flights-date-past");
        assertCode(() -> FlightQuery.of("PAR", "ABJ", "12/2026", null, false, null, CLOCK), "flights-invalid-date");
        assertCode(() -> FlightQuery.of("PAR", "ABJ", "2026-12-40", null, false, null, CLOCK), "flights-invalid-date");
        assertCode(() -> FlightQuery.of("PAR", "ABJ", "2026-12-20", "2026-12-19", false, null, CLOCK),
                "flights-return-before-departure");
        assertCode(() -> FlightQuery.of("PAR", "ABJ", "2026-12", "2026-11", false, null, CLOCK),
                "flights-return-before-departure");
    }

    @Test
    void rejectsUnsupportedCurrency() {
        assertCode(() -> FlightQuery.of("PAR", "ABJ", null, null, false, "BTC", CLOCK), "flights-invalid-currency");
    }

    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
        assertThatThrownBy(call)
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    @Test
    void acceptsAPreciseDay_andTodayItself() {
        FlightQuery q = FlightQuery.of("PAR", "ABJ", "2026-12-12", "2027-01-08", false, null, CLOCK);

        assertThat(q.departure()).isEqualTo(FlightDate.ofDay(java.time.LocalDate.of(2026, 12, 12)));
        assertThat(q.departure().providerValue()).isEqualTo("2026-12-12");
        assertThat(q.returnDate().providerValue()).isEqualTo("2027-01-08");
        assertThat(q.key()).isEqualTo("PAR-ABJ|2026-12-12|2027-01-08|false|EUR");
        assertThat(FlightQuery.of("PAR", "ABJ", "2026-10-15", null, false, null, CLOCK).departure().isDay()).isTrue();
    }

    private static String path(String departure, String returnDate) {
        return FlightQuery.of("par", "abj", departure, returnDate, false, null, CLOCK)
                .searchPath(java.time.LocalDate.now(CLOCK));
    }

    @Test
    void searchPath_dayOneWayAndRoundTrip() {
        assertThat(path("2026-12-12", null)).isEqualTo("/search/PAR1212ABJ1");
        assertThat(path("2026-12-12", "2027-01-08")).isEqualTo("/search/PAR1212ABJ08011");
        assertThat(path("2026-10-15", null)).isEqualTo("/search/PAR1510ABJ1");
    }

    @Test
    void searchPath_futureMonth_usesTheFirstDay() {
        assertThat(path("2026-12", null)).isEqualTo("/search/PAR0112ABJ1");
        assertThat(path("2026-12", "2027-01")).isEqualTo("/search/PAR0112ABJ01011");
    }

    @Test
    void searchPath_isNull_withoutAUsableDay() {
        assertThat(path(null, null)).isNull();
        assertThat(path("2026-10", null)).isNull();
        assertThat(path("2026-10-20", "2026-10")).isNull();
        assertThat(path("2026-12-20", "2026-12")).isNull();
    }
}
