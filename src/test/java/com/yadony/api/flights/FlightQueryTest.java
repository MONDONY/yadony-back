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
        assertThat(q.departureMonth()).isEqualTo(YearMonth.of(2026, 12));
        assertThat(q.returnMonth()).isNull();
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
        assertThat(FlightQuery.of("PAR", "ABJ", "2026-10", null, false, "EUR", CLOCK).departureMonth())
                .isEqualTo(YearMonth.of(2026, 10));
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
        assertCode(() -> FlightQuery.of("PAR", "ABJ", "2026-09", null, false, null, CLOCK), "flights-month-past");
        assertCode(() -> FlightQuery.of("PAR", "ABJ", null, "2026-01", false, null, CLOCK), "flights-month-past");
        assertCode(() -> FlightQuery.of("PAR", "ABJ", "12/2026", null, false, null, CLOCK), "flights-invalid-month");
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
}
