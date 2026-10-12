package com.yadony.api.flights;

import com.yadony.api.flights.dto.FlightOfferDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class FlightOfferMapperTest {

    private static final String TEMPLATE = "https://logos.test/{code}@png?rs=fit:200:200";

    @Mock AirlineDirectory airlines;
    @Mock AirportTimeZones timeZones;

    private TravelpayoutsPrice priceOf(String airline) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", "2026-12-04T11:30:00+01:00", null,
                new BigDecimal("327"), airline, "781", 1, null, 580, "Kiwi.com", "/search/PAR0412ABJ1");
    }

    private FlightOfferDto map(String template, String airline) {
        lenient().when(airlines.nameOf(any(), any())).thenReturn("Compagnie");
        return new FlightOfferMapper(airlines, timeZones, template).toDto(priceOf(airline), "EUR", "fr");
    }

    @Test
    void logoUrl_isBuiltFromTheUppercasedIataCode() {
        assertThat(map(TEMPLATE, "at").logoUrl()).isEqualTo("https://logos.test/AT@png?rs=fit:200:200");
    }

    @Test
    void logoUrl_isNullWhenTemplateIsBlank() {
        assertThat(map("", "AT").logoUrl()).isNull();
        assertThat(map(null, "AT").logoUrl()).isNull();
    }

    @Test
    void logoUrl_isNullWhenAirlineCodeIsMissing() {
        assertThat(map(TEMPLATE, null).logoUrl()).isNull();
    }

    @Test
    void logoUrl_isNullWhenPartnerCodeIsNotAnIataCode() {
        assertThat(map(TEMPLATE, "../x?y").logoUrl()).isNull();
        assertThat(map(TEMPLATE, "ABCD").logoUrl()).isNull();
    }

    private FlightOfferMapper mapper() {
        lenient().when(airlines.nameOf(any(), any())).thenReturn("Compagnie");
        return new FlightOfferMapper(airlines, timeZones, TEMPLATE);
    }

    private static TravelpayoutsPrice roundTrip(Integer durationTo, Integer durationBack) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", "2026-12-12T11:30:00+01:00",
                "2027-01-08T23:55:00Z", new BigDecimal("780"), "AF", "702", 0, 0, 900, "Air France",
                "/search/PAR1212ABJ08011", durationTo, durationBack);
    }

    @Test
    void roundTrip_arrivalsAreInTheLocalTimeOfEachArrivalAirport() {
        lenient().when(timeZones.zoneOf("ABJ", "ABJ")).thenReturn(java.time.ZoneId.of("Africa/Abidjan"));
        lenient().when(timeZones.zoneOf("CDG", "PAR")).thenReturn(java.time.ZoneId.of("Europe/Paris"));

        FlightOfferDto dto = mapper().toDto(roundTrip(375, 380), "EUR", "fr");

        assertThat(dto.durationMinutes()).isEqualTo(900);
        assertThat(dto.outboundDurationMinutes()).isEqualTo(375);
        assertThat(dto.returnDurationMinutes()).isEqualTo(380);
        // 11:30 Paris (10:30 UTC) + 6 h 15 = 16:45 UTC, Abidjan est en UTC.
        assertThat(dto.arrivalAt()).isEqualTo("2026-12-12T16:45:00Z");
        // 23:55 UTC + 6 h 20 = 06:15 UTC le lendemain, 07:15 à Paris (UTC+1 en hiver).
        assertThat(dto.returnArrivalAt()).isEqualTo("2027-01-09T07:15:00+01:00");
    }

    @Test
    void oneWay_hasNoReturnFields_evenIfThePartnerSendsDurationBack() {
        lenient().when(timeZones.zoneOf("ABJ", "ABJ")).thenReturn(java.time.ZoneId.of("Africa/Abidjan"));
        TravelpayoutsPrice oneWay = new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", "2026-12-12T11:30:00+01:00",
                null, new BigDecimal("400"), "AF", "702", 0, null, 375, "Air France", "/search/PAR1212ABJ1", 375, 0);

        FlightOfferDto dto = mapper().toDto(oneWay, "EUR", "fr");

        assertThat(dto.arrivalAt()).isEqualTo("2026-12-12T16:45:00Z");
        assertThat(dto.returnDurationMinutes()).isNull();
        assertThat(dto.returnArrivalAt()).isNull();
    }

    @Test
    void unknownZoneOrMissingDuration_givesNoArrival() {
        FlightOfferDto unknownZone = mapper().toDto(roundTrip(375, 380), "EUR", "fr");
        assertThat(unknownZone.arrivalAt()).isNull();
        assertThat(unknownZone.returnArrivalAt()).isNull();
        assertThat(unknownZone.outboundDurationMinutes()).isEqualTo(375);

        FlightOfferDto noDuration = mapper().toDto(roundTrip(null, null), "EUR", "fr");
        assertThat(noDuration.arrivalAt()).isNull();
        assertThat(noDuration.outboundDurationMinutes()).isNull();
        assertThat(noDuration.returnArrivalAt()).isNull();
    }

    @Test
    void arrival_rejectsUnreadableOrIncompleteInput() {
        lenient().when(timeZones.zoneOf(any(), any())).thenReturn(java.time.ZoneId.of("Africa/Dakar"));
        FlightOfferMapper mapper = mapper();

        assertThat(mapper.arrival(null, 60, "DSS", "DKR")).isNull();
        assertThat(mapper.arrival("2026-12-12T11:30:00+01:00", -5, "DSS", "DKR")).isNull();
        assertThat(mapper.arrival("2026-12-12", 60, "DSS", "DKR")).isNull();
        assertThat(mapper.arrival("2026-12-12T23:30:00Z", 90, "DSS", "DKR")).isEqualTo("2026-12-13T01:00:00Z");
    }
}
