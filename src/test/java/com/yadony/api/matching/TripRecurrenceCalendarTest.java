package com.yadony.api.matching;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripRecurrenceCalendarTest {

    private final TripRecurrenceCalendar calendar = new TripRecurrenceCalendar();

    @Test
    void matchesStartAndEndDatesInclusively() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        LocalDate end = LocalDate.of(2026, 9, 15);

        assertThat(calendar.matches(start, start, end, "0100000", 1)).isTrue();
        assertThat(calendar.matches(end, start, end, "0100000", 1)).isTrue();
        assertThat(calendar.matches(start.minusDays(7), start, end, "0100000", 1)).isFalse();
        assertThat(calendar.matches(end.plusDays(7), start, end, "0100000", 1)).isFalse();
    }

    @Test
    void mapsWeekdayBitsFromMondayThroughSunday() {
        LocalDate monday = LocalDate.of(2026, 8, 31);

        IntStream.range(0, 7).forEach(index -> {
            String weekdays = "0".repeat(index) + "1" + "0".repeat(6 - index);
            assertThat(calendar.matches(
                    monday.plusDays(index), monday, null, weekdays, 1))
                    .as("weekday bit %s", index)
                    .isTrue();
        });
    }

    @Test
    void anchorsMultiWeekIntervalsToStartWeek() {
        LocalDate start = LocalDate.of(2026, 9, 1);

        assertThat(calendar.matches(LocalDate.of(2026, 9, 14), start, null, "1000000", 2)).isTrue();
        assertThat(calendar.matches(LocalDate.of(2026, 9, 7), start, null, "1000000", 2)).isFalse();
        assertThat(calendar.matches(LocalDate.of(2026, 9, 21), start, null, "1000000", 3)).isTrue();
        assertThat(calendar.matches(LocalDate.of(2026, 9, 28), start, null, "1000000", 4)).isTrue();
    }

    @Test
    void keepsIntervalAcrossIsoYearRollover() {
        LocalDate start = LocalDate.of(2026, 12, 28);

        assertThat(calendar.matches(LocalDate.of(2027, 1, 11), start, null, "1000000", 2)).isTrue();
        assertThat(calendar.matches(LocalDate.of(2027, 1, 4), start, null, "1000000", 2)).isFalse();
    }

    @Test
    void returnsOccurrencesAndTheirPublicationDatesWithoutAnEndDate() {
        var schedule = new TripRecurrenceCalendar.Schedule(
                LocalDate.of(2026, 9, 1), null, "1000100", 2, 14);

        assertThat(calendar.nextOccurrences(LocalDate.of(2026, 8, 20), 3, schedule))
                .extracting(TripRecurrenceCalendar.OccurrenceDate::departureDate)
                .containsExactly(
                        LocalDate.of(2026, 9, 4),
                        LocalDate.of(2026, 9, 14),
                        LocalDate.of(2026, 9, 18));
        assertThat(calendar.nextOccurrences(LocalDate.of(2026, 8, 20), 1, schedule))
                .singleElement()
                .satisfies(occurrence -> assertThat(occurrence.publicationDate())
                        .isEqualTo(LocalDate.of(2026, 8, 21)));
    }

    @Test
    void stopsAtTheOptionalEndDate() {
        var schedule = new TripRecurrenceCalendar.Schedule(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 10), "0010000", 1, 7);

        assertThat(calendar.nextOccurrences(LocalDate.of(2026, 9, 1), 5, schedule))
                .extracting(TripRecurrenceCalendar.OccurrenceDate::departureDate)
                .containsExactly(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 9));
    }

    @Test
    void rejectsMalformedSchedules() {
        LocalDate start = LocalDate.of(2026, 9, 1);

        assertThatThrownBy(() -> calendar.matches(start, start, null, "100", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.matches(start, start, null, "1000002", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.matches(start, start, null, "1000000", 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TripRecurrenceCalendar.Schedule(
                start, start.minusDays(1), "1000000", 1, 14))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
