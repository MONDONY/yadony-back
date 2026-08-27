package com.yadony.api.matching;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TripRecurrenceCalendar {

    public boolean matches(
            LocalDate candidate,
            LocalDate startDate,
            LocalDate endDate,
            String weekdays,
            int weekInterval
    ) {
        validateSchedule(startDate, endDate, weekdays, weekInterval);
        Objects.requireNonNull(candidate, "candidate");

        if (candidate.isBefore(startDate) || endDate != null && candidate.isAfter(endDate)) {
            return false;
        }

        int weekdayIndex = candidate.getDayOfWeek().getValue() - 1;
        if (weekdays.charAt(weekdayIndex) != '1') {
            return false;
        }

        LocalDate anchorMonday = startDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate candidateMonday = candidate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        long weeksSinceStart = ChronoUnit.WEEKS.between(anchorMonday, candidateMonday);
        return weeksSinceStart % weekInterval == 0;
    }

    public List<OccurrenceDate> nextOccurrences(LocalDate from, int count, Schedule schedule) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(schedule, "schedule");
        if (count < 0) {
            throw new IllegalArgumentException("count must be positive or zero");
        }

        List<OccurrenceDate> occurrences = new ArrayList<>(count);
        LocalDate candidate = from.isAfter(schedule.startDate()) ? from : schedule.startDate();
        while (occurrences.size() < count
                && (schedule.endDate() == null || !candidate.isAfter(schedule.endDate()))) {
            if (matches(
                    candidate,
                    schedule.startDate(),
                    schedule.endDate(),
                    schedule.weekdays(),
                    schedule.weekInterval())) {
                occurrences.add(new OccurrenceDate(
                        candidate,
                        candidate.minusDays(schedule.publicationLeadDays())));
            }
            candidate = candidate.plusDays(1);
        }
        return List.copyOf(occurrences);
    }

    private static void validateSchedule(
            LocalDate startDate,
            LocalDate endDate,
            String weekdays,
            int weekInterval
    ) {
        Objects.requireNonNull(startDate, "startDate");
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        if (weekdays == null || !weekdays.matches("[01]{7}") || !weekdays.contains("1")) {
            throw new IllegalArgumentException("weekdays must select at least one day");
        }
        if (weekInterval < 1 || weekInterval > 4) {
            throw new IllegalArgumentException("weekInterval must be between 1 and 4");
        }
    }

    public record Schedule(
            LocalDate startDate,
            LocalDate endDate,
            String weekdays,
            int weekInterval,
            int publicationLeadDays
    ) {
        public Schedule {
            validateSchedule(startDate, endDate, weekdays, weekInterval);
            if (publicationLeadDays < 1 || publicationLeadDays > 60) {
                throw new IllegalArgumentException("publicationLeadDays must be between 1 and 60");
            }
        }
    }

    public record OccurrenceDate(LocalDate departureDate, LocalDate publicationDate) {
        public OccurrenceDate {
            Objects.requireNonNull(departureDate, "departureDate");
            Objects.requireNonNull(publicationDate, "publicationDate");
        }
    }
}
