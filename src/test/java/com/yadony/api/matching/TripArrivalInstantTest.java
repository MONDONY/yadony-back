package com.yadony.api.matching;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripArrivalInstantTest {

    private final AnnouncementRepository repository = mock(AnnouncementRepository.class);
    private final TripArrivalInstant helper = new TripArrivalInstant(repository);

    private AnnouncementEntity trip(LocalDate arrivalDate, LocalTime arrivalTime) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setDepartureCity("Paris");
        a.setArrivalCity("Abidjan");
        a.setArrivalCountryCode("CI");
        a.setTimezone("Europe/Paris");
        a.setDepartureDate(LocalDate.of(2026, 7, 1));
        a.setArrivalDate(arrivalDate);
        a.setArrivalTime(arrivalTime);
        return a;
    }

    @Test
    void usesArrivalCityTimezoneNotDepartureOne() {
        when(repository.findTimezoneByCityName("Abidjan", "CI")).thenReturn(Optional.of("Africa/Abidjan"));
        Optional<OffsetDateTime> got = helper.of(trip(LocalDate.of(2026, 7, 2), LocalTime.of(6, 30)));
        // Abidjan = UTC : 06:30 local = 06:30Z (et non 04:30Z comme à Paris en été)
        assertThat(got).contains(OffsetDateTime.of(2026, 7, 2, 6, 30, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void fallsBackToTripTimezoneWhenCityUnknown() {
        when(repository.findTimezoneByCityName("Abidjan", "CI")).thenReturn(Optional.empty());
        Optional<OffsetDateTime> got = helper.of(trip(LocalDate.of(2026, 7, 2), LocalTime.of(6, 30)));
        assertThat(got).contains(OffsetDateTime.of(2026, 7, 2, 6, 30, 0, 0, ZoneOffset.ofHours(2)));
    }

    @Test
    void usesDepartureDayWhenNoArrivalDate() {
        when(repository.findTimezoneByCityName("Abidjan", "CI")).thenReturn(Optional.of("Africa/Abidjan"));
        Optional<OffsetDateTime> got = helper.of(trip(null, LocalTime.of(23, 0)));
        assertThat(got).contains(OffsetDateTime.of(2026, 7, 1, 23, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    void emptyWithoutArrivalTime() {
        assertThat(helper.of(trip(LocalDate.of(2026, 7, 2), null))).isEmpty();
    }
}
