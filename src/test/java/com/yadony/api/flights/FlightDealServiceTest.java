package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.flights.FlightCorridorQueryRepository.CityPair;
import com.yadony.api.flights.FlightCorridorQueryRepository.CodePair;
import com.yadony.api.flights.dto.FlightOfferDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.yadony.api.flights.FlightTestData.price;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightDealServiceTest {

    @Mock FlightPriceService prices;
    @Mock FlightPlaceService places;
    @Mock FlightCorridorQueryRepository corridors;
    @Mock FlightOfferMapper mapper;
    @Mock FlightUsers users;

    FlightDealService service;
    final UUID owner = UUID.randomUUID();
    static final String UID = "uid-deals";

    @BeforeEach
    void setUp() {
        service = new FlightDealService(prices, places, corridors, mapper, users, "PAR-ABJ, par-dkr");
        lenient().when(prices.isAvailable()).thenReturn(true);
        lenient().when(users.resolve(UID)).thenReturn(new FlightUsers.Caller(owner, "fr"));
        lenient().when(mapper.toDto(any(), anyString(), anyString())).thenAnswer(inv -> {
            TravelpayoutsPrice p = inv.getArgument(0);
            return new FlightOfferDto(p.origin(), p.destination(), null, null, null, null, p.price(),
                    inv.getArgument(1), null, null, null, null, null, null, null, p.link(), null);
        });
    }

    private static List<TravelpayoutsPrice> dealPrices(String origin, String destination) {
        return List.of(price(origin, destination, "300"), price(origin, destination, "500"),
                price(origin, destination, "520"), price(origin, destination, "560"));
    }

    private static List<TravelpayoutsPrice> flatPrices(String origin, String destination) {
        return List.of(price(origin, destination, "500"), price(origin, destination, "500"),
                price(origin, destination, "510"), price(origin, destination, "520"));
    }

    @Test
    void personalRoutes_searchesFirstThenResolvedCorridors_withoutDuplicates() {
        when(corridors.recentSearchedRoutes(owner, FlightDealService.MAX_ROUTES))
                .thenReturn(List.of(new CodePair("PAR", "ABJ")));
        when(corridors.recentCityCorridors(eq(owner), anyInt())).thenReturn(List.of(
                new CityPair("Paris", "Abidjan"),
                new CityPair("Lyon", "Dakar"),
                new CityPair("Paris", "Village inconnu"),
                new CityPair("Paris", "Paris")));
        when(places.resolveCity("Paris")).thenReturn(Optional.of("PAR"));
        when(places.resolveCity("Abidjan")).thenReturn(Optional.of("ABJ"));
        when(places.resolveCity("Lyon")).thenReturn(Optional.of("LYS"));
        when(places.resolveCity("Dakar")).thenReturn(Optional.of("DKR"));
        when(places.resolveCity("Village inconnu")).thenReturn(Optional.empty());

        assertThat(service.personalRoutes(owner))
                .containsExactly(new CodePair("PAR", "ABJ"), new CodePair("LYS", "DKR"));
    }

    @Test
    void deals_personalized_keepsOnlyRealDeals_sortedByDiscount() {
        when(corridors.recentSearchedRoutes(owner, FlightDealService.MAX_ROUTES))
                .thenReturn(List.of(new CodePair("PAR", "ABJ"), new CodePair("PAR", "DKR")));
        when(prices.prices(any())).thenAnswer(inv -> {
            FlightQuery q = inv.getArgument(0);
            return q.destination().equals("ABJ") ? dealPrices("PAR", "ABJ") : flatPrices("PAR", "DKR");
        });

        var response = service.deals(UID, "eur");

        assertThat(response.personalized()).isTrue();
        assertThat(response.currency()).isEqualTo("EUR");
        assertThat(response.deals()).singleElement().satisfies(d -> {
            assertThat(d.offer().destinationCode()).isEqualTo("ABJ");
            assertThat(d.discountPercent()).isEqualTo(41);
        });
    }

    @Test
    void deals_withoutCorridors_usesDefaultRoutes() {
        when(corridors.recentSearchedRoutes(owner, FlightDealService.MAX_ROUTES)).thenReturn(List.of());
        when(corridors.recentCityCorridors(eq(owner), anyInt())).thenReturn(List.of());
        when(prices.prices(any())).thenReturn(dealPrices("PAR", "ABJ"));

        var response = service.deals(UID, null);

        assertThat(response.personalized()).isFalse();
        assertThat(response.deals()).hasSize(2);
        verify(prices, times(2)).prices(any());
    }

    @Test
    void deals_cachedPerUser() {
        when(corridors.recentSearchedRoutes(owner, FlightDealService.MAX_ROUTES))
                .thenReturn(List.of(new CodePair("PAR", "ABJ")));
        when(prices.prices(any())).thenReturn(dealPrices("PAR", "ABJ"));

        service.deals(UID, "EUR");
        service.deals(UID, "EUR");

        verify(prices, times(1)).prices(any());
    }

    @Test
    void deals_allRoutesFailing_is502() {
        when(corridors.recentSearchedRoutes(owner, FlightDealService.MAX_ROUTES))
                .thenReturn(List.of(new CodePair("PAR", "ABJ")));
        when(prices.prices(any())).thenThrow(new FlightProviderException("down"));

        assertThatThrownBy(() -> service.deals(UID, "EUR"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flights-provider-error");
    }

    @Test
    void deals_oneRouteFailing_returnsTheOthers_withoutCaching() {
        when(corridors.recentSearchedRoutes(owner, FlightDealService.MAX_ROUTES))
                .thenReturn(List.of(new CodePair("PAR", "ABJ"), new CodePair("PAR", "DKR")));
        when(prices.prices(any())).thenAnswer(inv -> {
            FlightQuery q = inv.getArgument(0);
            if (q.destination().equals("DKR")) throw new FlightProviderException("down");
            return dealPrices("PAR", "ABJ");
        });

        assertThat(service.deals(UID, "EUR").deals()).hasSize(1);
        service.deals(UID, "EUR");
        // Réponse partielle non mise en cache : les deux trajets sont redemandés.
        verify(prices, times(4)).prices(any());
    }

    @Test
    void deals_unconfigured_is503() {
        when(prices.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.deals(UID, "EUR"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode())
                .isEqualTo("flights-unavailable");
    }

    @Test
    void parseRoutes_ignoresMalformedEntries() {
        assertThat(FlightDealService.parseRoutes("PAR-ABJ,PARIS-ABJ, ,dkr-par,ABJ"))
                .containsExactly(new CodePair("PAR", "ABJ"), new CodePair("DKR", "PAR"));
        assertThat(FlightDealService.parseRoutes(null)).isEmpty();
    }

    @Test
    void deals_guest_usesDefaultRoutes_withoutReadingAnyHistory() {
        when(users.resolve("guest")).thenReturn(new FlightUsers.Caller(null, "fr"));
        when(prices.prices(any())).thenReturn(dealPrices("PAR", "ABJ"));

        var response = service.deals("guest", "EUR");

        assertThat(response.personalized()).isFalse();
        verifyNoInteractions(corridors);
    }
}
