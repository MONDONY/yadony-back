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
    @Mock FlightUsers users;
    @Mock FlightSearchRepository searches;

    FlightSearchService service;
    final UUID owner = UUID.randomUUID();
    static final String UID = "uid-search";

    @BeforeEach
    void setUp() {
        service = new FlightSearchService(prices, new FlightOfferMapper(airlines, LOGO_TEMPLATE), users, searches, CLOCK);
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

        assertThat(service.search(UID, "PAR", "ABJ", null, null, false, null).offers()).hasSize(1);
    }

    @Test
    void search_unconfigured_is503_beforeAnyValidation() {
        when(prices.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.search(UID, "bad", "bad", null, null, false, null))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flights-unavailable");
    }
}
