package com.yadony.api.flights;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.yadony.api.flights.FlightTestData.price;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Cache des prix et annuaire des compagnies. */
class FlightPriceAndAirlineTest {

    private final TravelpayoutsClient client = mock(TravelpayoutsClient.class);
    /** Direct uniquement : un seul appel, la fusion avec l'appel des directs est testée dans FlightPriceServiceTest. */
    private final FlightQuery query = new FlightQuery("PAR", "ABJ", null, null, true, "EUR");

    @Test
    void prices_areCached_andInvalidRowsDropped() {
        TravelpayoutsPrice zero = new TravelpayoutsPrice("PAR", "ABJ", null, null, null, null,
                java.math.BigDecimal.ZERO, null, null, null, null, null, null, null);
        when(client.pricesForDates(eq("PAR"), eq("ABJ"), any(), any(), anyBoolean(), eq("EUR"), anyInt(), anyInt()))
                .thenReturn(List.of(price("327"), zero));
        FlightPriceService service = new FlightPriceService(client, Duration.ofMinutes(30));

        assertThat(service.prices(query)).hasSize(1);
        assertThat(service.prices(query)).hasSize(1);
        verify(client, times(1)).pricesForDates(anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyInt(), anyInt());
    }

    @Test
    void prices_failuresAreNotCached() {
        when(client.pricesForDates(anyString(), anyString(), any(), any(), anyBoolean(), anyString(), anyInt(), anyInt()))
                .thenThrow(new FlightProviderException("down"))
                .thenReturn(List.of(price("327")));
        FlightPriceService service = new FlightPriceService(client);

        assertThatThrownBy(() -> service.prices(query)).isInstanceOf(FlightProviderException.class);
        assertThat(service.prices(query)).hasSize(1);
    }

    @Test
    void isAvailable_followsClientConfiguration() {
        when(client.isConfigured()).thenReturn(true);
        assertThat(new FlightPriceService(client).isAvailable()).isTrue();
    }

    @Test
    void airlineNames_loadedOncePerLanguage_withCodeFallback() {
        when(client.isConfigured()).thenReturn(true);
        when(client.airlineNames("fr")).thenReturn(Map.of("AT", "Royal Air Maroc"));
        AirlineDirectory directory = new AirlineDirectory(client);

        assertThat(directory.nameOf("at", "fr")).isEqualTo("Royal Air Maroc");
        assertThat(directory.nameOf("KQ", "fr")).isEqualTo("KQ");
        assertThat(directory.nameOf(null, "fr")).isNull();
        verify(client, times(1)).airlineNames("fr");
    }

    @Test
    void airlineNames_failureFallsBackToCodes_andIsNotRetriedImmediately() {
        when(client.isConfigured()).thenReturn(true);
        when(client.airlineNames("en")).thenThrow(new FlightProviderException("down"));
        AirlineDirectory directory = new AirlineDirectory(client);

        assertThat(directory.nameOf("KQ", "en")).isEqualTo("KQ");
        assertThat(directory.nameOf("KQ", "en")).isEqualTo("KQ");
        verify(client, times(1)).airlineNames("en");
    }

    @Test
    void airlineNames_emptyListOrUnconfigured_fallsBackToCodes() {
        when(client.isConfigured()).thenReturn(true);
        when(client.airlineNames("fr")).thenReturn(Map.of());
        assertThat(new AirlineDirectory(client).nameOf("KQ", "fr")).isEqualTo("KQ");

        TravelpayoutsClient off = mock(TravelpayoutsClient.class);
        assertThat(new AirlineDirectory(off).nameOf("KQ", "fr")).isEqualTo("KQ");
        verify(off, never()).airlineNames(anyString());
    }

    /** Régression : Corsair s'affichait « SS » et Mauritania Airlines « L6 » faute de nom dans la liste. */
    @Test
    void airlineNames_corridorAirlinesMissingFromList_useStaticFallback() {
        when(client.isConfigured()).thenReturn(true);
        when(client.airlineNames("fr")).thenReturn(Map.of("AF", "Air France"));
        AirlineDirectory directory = new AirlineDirectory(client);

        assertThat(directory.nameOf("ss", "fr")).isEqualTo("Corsair");
        assertThat(directory.nameOf("L6", "fr")).isEqualTo("Mauritania Airlines");
        assertThat(directory.nameOf("HC", "fr")).isEqualTo("Air Sénégal");
        assertThat(directory.nameOf("AF", "fr")).isEqualTo("Air France");
        assertThat(directory.nameOf("KQ", "fr")).isEqualTo("KQ");
    }

    @Test
    void airlineNames_listNameWinsOverStaticFallback_andFallbackServesWhenListIsDown() {
        when(client.isConfigured()).thenReturn(true);
        when(client.airlineNames("en")).thenReturn(Map.of("SS", "Corsair International"));
        assertThat(new AirlineDirectory(client).nameOf("SS", "en")).isEqualTo("Corsair International");

        TravelpayoutsClient down = mock(TravelpayoutsClient.class);
        when(down.isConfigured()).thenReturn(true);
        when(down.airlineNames("fr")).thenThrow(new FlightProviderException("down"));
        assertThat(new AirlineDirectory(down).nameOf("KP", "fr")).isEqualTo("ASKY Airlines");
    }

    @Test
    void corridorFallback_neverMapsDnToAirSenegal() {
        // Dans airlines.json, DN est Dan Air : Air Sénégal est HC.
        assertThat(AirlineDirectory.CORRIDOR_AIRLINES).doesNotContainKey("DN").containsKey("HC");
    }
}
