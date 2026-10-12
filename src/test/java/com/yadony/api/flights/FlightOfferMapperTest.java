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

    private TravelpayoutsPrice priceOf(String airline) {
        return new TravelpayoutsPrice("PAR", "ABJ", "CDG", "ABJ", "2026-12-04T11:30:00+01:00", null,
                new BigDecimal("327"), airline, "781", 1, null, 580, "Kiwi.com", "/search/PAR0412ABJ1");
    }

    private FlightOfferDto map(String template, String airline) {
        lenient().when(airlines.nameOf(any(), any())).thenReturn("Compagnie");
        return new FlightOfferMapper(airlines, template).toDto(priceOf(airline), "EUR", "fr");
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
}
