package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yadony.api.flights.FlightTestData.CLOCK;
import static com.yadony.api.flights.FlightTestData.price;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightSearchServiceTest {

    private static final String LOGO_TEMPLATE = "https://logos.test/{code}.png";

    @Mock FlightPriceService prices;
    @Mock AirlineDirectory airlines;
    @Mock AirportTimeZones timeZones;
    @Mock FlightUsers users;
    @Mock FlightSearchRepository searches;

    FlightSearchService service;
    final UUID owner = UUID.randomUUID();
    static final String UID = "uid-search";

    @BeforeEach
    void setUp() {
        service = new FlightSearchService(prices, new FlightOfferMapper(airlines, timeZones, LOGO_TEMPLATE), users, searches, CLOCK);
        lenient().when(prices.isAvailable()).thenReturn(true);
        lenient().when(users.resolve(UID)).thenReturn(new FlightUsers.Caller(owner, "en"));
        lenient().when(airlines.nameOf(anyString(), anyString())).thenAnswer(inv ->
                Map.of("AT", "Royal Air Maroc").getOrDefault(inv.<String>getArgument(0), inv.getArgument(0)));
    }

    @Test
    void search_returnsMappedOffers_andRemembersTheRoute() {
        when(prices.prices(any())).thenReturn(List.of(price("327"), price("410")));

        var response = service.search(UID, "par", "abj", "2026-12", "2027-01", false, "eur");

        assertThat(response.originCode()).isEqualTo("PAR");
        assertThat(response.roundTrip()).isTrue();
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.offers()).hasSize(2);
        assertThat(response.searchPath()).isEqualTo("/search/PAR0112ABJ01011");
        var first = response.offers().get(0);
        assertThat(first.airlineName()).isEqualTo("Royal Air Maroc");
        assertThat(first.currency()).isEqualTo("EUR");
        assertThat(first.offerPath()).startsWith("/search/");
        assertThat(first.durationMinutes()).isEqualTo(580);
        assertThat(first.logoUrl()).isEqualTo("https://logos.test/AT.png");

        ArgumentCaptor<FlightSearchEntity> saved = ArgumentCaptor.forClass(FlightSearchEntity.class);
        verify(searches).save(saved.capture());
        assertThat(saved.getValue().getOwnerId()).isEqualTo(owner);
        assertThat(saved.getValue().getDestinationCode()).isEqualTo("ABJ");
        assertThat(saved.getValue().isRoundTrip()).isTrue();
    }

    @Test
    void search_providerDown_is502_andNothingIsRemembered() {
        when(prices.prices(any())).thenThrow(new FlightProviderException("down"));

        assertThatThrownBy(() -> service.search(UID, "PAR", "ABJ", null, null, false, null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flights-provider-error");
        verify(searches, never()).save(any());
    }

    @Test
    void search_rememberFailure_doesNotFailTheSearch() {
        when(prices.prices(any())).thenReturn(List.of(price("327")));
        when(searches.save(any())).thenThrow(new RuntimeException("db down"));

        var response = service.search(UID, "PAR", "ABJ", null, null, false, null);
        assertThat(response.offers()).hasSize(1);
        assertThat(response.searchPath()).isNull();
    }

    @Test
    void search_unconfigured_is503_beforeAnyValidation() {
        when(prices.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.search(UID, "bad", "bad", null, null, false, null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flights-unavailable");
    }

    @Test
    void search_guest_isAnonymous_andNothingIsRemembered() {
        when(users.resolve("guest")).thenReturn(new FlightUsers.Caller(null, "fr"));
        when(prices.prices(any())).thenReturn(List.of(price("327")));

        var response = service.search("guest", "PAR", "ABJ", "2026-12-12", null, false, null);

        assertThat(response.offers()).hasSize(1);
        assertThat(response.searchPath()).isEqualTo("/search/PAR1212ABJ1");
        verifyNoInteractions(searches);
    }

    /** Régression : ABJ ⇄ PAR du 23/12 au 02/01 en jours précis rendait 0 offre (couple absent du cache Aviasales). */
    @Test
    void exactDays_withoutOffers_fallBackToNearbyDatesInTheMonths() {
        when(prices.prices(any())).thenAnswer(inv -> {
            FlightQuery q = inv.getArgument(0);
            if (q.departure().isDay()) return List.of();
            return List.of(
                    FlightNearbyDatesTest.offer("2026-12-24T23:55:00+00:00", "2027-01-02T09:00:00+01:00", "650"),
                    FlightNearbyDatesTest.offer("2026-12-10T10:00:00+00:00", "2027-01-02T09:00:00+01:00", "300"),
                    FlightNearbyDatesTest.offer("2026-12-23T10:00:00+00:00", "2027-01-04T09:00:00+01:00", "700"));
        });

        var response = service.search(UID, "ABJ", "PAR", "2026-12-23", "2027-01-02", false, "EUR");

        assertThat(response.nearbyDates()).isTrue();
        assertThat(response.nearbyDaysWindow()).isEqualTo(3);
        assertThat(response.offers()).extracting(o -> o.price().toPlainString()).containsExactly("650", "700");
        assertThat(response.offers().get(0).airlineCode()).isEqualTo("SS");
        assertThat(response.searchPath()).isEqualTo("/search/ABJ2312PAR02011");

        ArgumentCaptor<FlightQuery> asked = ArgumentCaptor.forClass(FlightQuery.class);
        verify(prices, times(2)).prices(asked.capture());
        assertThat(asked.getAllValues().get(0).departure().providerValue()).isEqualTo("2026-12-23");
        assertThat(asked.getAllValues().get(0).returnDate().providerValue()).isEqualTo("2027-01-02");
        assertThat(asked.getAllValues().get(1).departure().providerValue()).isEqualTo("2026-12");
        assertThat(asked.getAllValues().get(1).returnDate().providerValue()).isEqualTo("2027-01");
        verify(searches).save(any());
    }

    @Test
    void exactDay_oneWay_widensOnlyTheDepartureMonth() {
        when(prices.prices(any())).thenAnswer(inv -> {
            FlightQuery q = inv.getArgument(0);
            if (q.departure().isDay()) return List.of();
            return List.of(FlightNearbyDatesTest.offer("2026-12-21T10:00:00+00:00", null, "420"));
        });

        var response = service.search(UID, "ABJ", "PAR", "2026-12-23", null, false, "EUR");

        assertThat(response.nearbyDates()).isTrue();
        assertThat(response.offers()).hasSize(1);
        ArgumentCaptor<FlightQuery> asked = ArgumentCaptor.forClass(FlightQuery.class);
        verify(prices, times(2)).prices(asked.capture());
        assertThat(asked.getAllValues().get(1).departure().providerValue()).isEqualTo("2026-12");
        assertThat(asked.getAllValues().get(1).returnDate()).isNull();
    }

    @Test
    void exactDays_withOffers_doNotFallBack() {
        when(prices.prices(any())).thenReturn(List.of(price("327")));

        var response = service.search(UID, "PAR", "ABJ", "2026-12-12", "2027-01-02", false, null);

        assertThat(response.offers()).hasSize(1);
        assertThat(response.nearbyDates()).isNull();
        assertThat(response.nearbyDaysWindow()).isNull();
        verify(prices, times(1)).prices(any());
    }

    @Test
    void wholeMonths_withoutOffers_doNotFallBack() {
        when(prices.prices(any())).thenReturn(List.of());

        var response = service.search(UID, "PAR", "ABJ", "2026-12", "2027-01", false, null);

        assertThat(response.offers()).isEmpty();
        assertThat(response.nearbyDates()).isNull();
        verify(prices, times(1)).prices(any());
    }

    @Test
    void nearbyDates_nothingCloseEnough_staysEmptyWithoutFlag() {
        when(prices.prices(any())).thenAnswer(inv -> {
            FlightQuery q = inv.getArgument(0);
            if (q.departure().isDay()) return List.of();
            return List.of(FlightNearbyDatesTest.offer("2026-12-01T10:00:00+00:00", "2027-01-02T09:00:00+01:00", "300"));
        });

        var response = service.search(UID, "ABJ", "PAR", "2026-12-23", "2027-01-02", false, "EUR");

        assertThat(response.offers()).isEmpty();
        assertThat(response.nearbyDates()).isNull();
        assertThat(response.nearbyDaysWindow()).isNull();
    }

    @Test
    void nearbyDates_providerFailure_isNotAnError() {
        when(prices.prices(any())).thenAnswer(inv -> {
            FlightQuery q = inv.getArgument(0);
            if (q.departure().isDay()) return List.of();
            throw new FlightProviderException("down");
        });

        var response = service.search(UID, "ABJ", "PAR", "2026-12-23", "2027-01-02", false, "EUR");

        assertThat(response.offers()).isEmpty();
        assertThat(response.nearbyDates()).isNull();
    }
}
