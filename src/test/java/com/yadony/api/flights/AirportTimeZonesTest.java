package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AirportTimeZonesTest {

    private final TravelpayoutsClient client = mock(TravelpayoutsClient.class);

    @Test
    void airportFirst_thenCity_loadedOncePerList() {
        when(client.isConfigured()).thenReturn(true);
        when(client.timeZones("airports")).thenReturn(Map.of("CDG", "Europe/Paris", "BAD", "Not/AZone"));
        when(client.timeZones("cities")).thenReturn(Map.of("ABJ", "Africa/Abidjan"));
        AirportTimeZones zones = new AirportTimeZones(client);

        assertThat(zones.zoneOf("cdg", "PAR")).isEqualTo(ZoneId.of("Europe/Paris"));
        assertThat(zones.zoneOf(null, "abj")).isEqualTo(ZoneId.of("Africa/Abidjan"));
        assertThat(zones.zoneOf("XXX", "ABJ")).isEqualTo(ZoneId.of("Africa/Abidjan"));
        assertThat(zones.zoneOf("BAD", null)).isNull();
        assertThat(zones.zoneOf(" ", "ZZZ")).isNull();
        verify(client, times(1)).timeZones("airports");
        verify(client, times(1)).timeZones("cities");
    }

    @Test
    void failureOrEmptyList_givesNoZone_andIsNotRetriedImmediately() {
        when(client.isConfigured()).thenReturn(true);
        when(client.timeZones("airports")).thenThrow(new FlightProviderException("down"));
        when(client.timeZones("cities")).thenReturn(Map.of());
        AirportTimeZones zones = new AirportTimeZones(client);

        assertThat(zones.zoneOf("ABJ", "ABJ")).isNull();
        assertThat(zones.zoneOf("ABJ", "ABJ")).isNull();
        verify(client, times(1)).timeZones("airports");
        verify(client, times(1)).timeZones("cities");
    }

    @Test
    void unconfigured_neverCallsThePartner() {
        AirportTimeZones zones = new AirportTimeZones(client);

        assertThat(zones.zoneOf("ABJ", "ABJ")).isNull();
        verify(client, never()).timeZones(anyString());
    }
}
