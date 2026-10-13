package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlightDateTest {

    @Test
    void parse_dayAndMonth() {
        FlightDate day = FlightDate.parse(" 2026-12-12 ");
        assertThat(day.isDay()).isTrue();
        assertThat(day.first()).isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(day.last()).isEqualTo(LocalDate.of(2026, 12, 12));
        assertThat(day.providerValue()).isEqualTo("2026-12-12");

        FlightDate month = FlightDate.parse("2026-02");
        assertThat(month.isDay()).isFalse();
        assertThat(month.first()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(month.last()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(month).hasToString("2026-02");
    }

    @Test
    void parse_blankIsNoDate_andGarbageFails() {
        assertThat(FlightDate.parse(null)).isNull();
        assertThat(FlightDate.parse("  ")).isNull();
        assertThatThrownBy(() -> FlightDate.parse("12/2026")).isInstanceOf(DateTimeParseException.class);
        assertThatThrownBy(() -> FlightDate.parse("2026-02-30")).isInstanceOf(DateTimeParseException.class);
    }

    @Test
    void exactlyOneOfDayOrMonth() {
        assertThatThrownBy(() -> new FlightDate(null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlightDate(LocalDate.of(2026, 1, 1), YearMonth.of(2026, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
