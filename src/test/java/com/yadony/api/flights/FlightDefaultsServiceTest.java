package com.yadony.api.flights;

import com.yadony.api.flights.FlightCorridorQueryRepository.CodePair;
import com.yadony.api.flights.dto.FlightPlaceDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlightDefaultsServiceTest {

    @Mock FlightCorridorQueryRepository corridors;
    @Mock FlightDealService deals;
    @Mock FlightPlaceService places;
    @Mock FlightUsers users;

    FlightDefaultsService service;
    final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new FlightDefaultsService(corridors, deals, places, users);
        lenient().when(users.resolve("uid")).thenReturn(new FlightUsers.Caller(owner, "fr"));
        lenient().when(places.suggest(any(), any())).thenReturn(List.of());
    }

    @Test
    void guest_getsNothing() {
        when(users.resolve("guest")).thenReturn(new FlightUsers.Caller(null, "fr"));

        var result = service.defaults("guest");

        assertThat(result.source()).isEqualTo("none");
        assertThat(result.origin()).isNull();
        verify(corridors, never()).recentSearchedRoutes(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void lastSearch_winsOverCorridors() {
        when(corridors.recentSearchedRoutes(owner, 1)).thenReturn(List.of(new CodePair("PAR", "DKR")));
        when(places.suggest("PAR", "fr")).thenReturn(List.of(new FlightPlaceDto("PAR", "Paris", "FR", "France")));

        var result = service.defaults("uid");

        assertThat(result.source()).isEqualTo("search");
        assertThat(result.origin().name()).isEqualTo("Paris");
        assertThat(result.destination().code()).isEqualTo("DKR");
        assertThat(result.destination().name()).isEqualTo("DKR");
        verify(deals, never()).personalRoutes(any(), anyInt());
    }

    @Test
    void withoutSearch_usesTheMostRecentCorridor() {
        when(corridors.recentSearchedRoutes(owner, 1)).thenReturn(List.of());
        when(deals.personalRoutes(owner, 1)).thenReturn(List.of(new CodePair("LYS", "ABJ"), new CodePair("PAR", "BKO")));

        var result = service.defaults("uid");

        assertThat(result.source()).isEqualTo("corridor");
        assertThat(result.origin().code()).isEqualTo("LYS");
        assertThat(result.destination().code()).isEqualTo("ABJ");
    }

    @Test
    void noHistory_isNone_andLabelFailuresNeverBlock() {
        when(corridors.recentSearchedRoutes(owner, 1)).thenReturn(List.of(new CodePair("PAR", "ABJ")));
        when(places.suggest(any(), any())).thenThrow(new RuntimeException("down"));

        assertThat(service.defaults("uid").origin().name()).isEqualTo("PAR");

        when(corridors.recentSearchedRoutes(owner, 1)).thenReturn(List.of());
        when(deals.personalRoutes(owner, 1)).thenReturn(List.of());
        assertThat(service.defaults("uid").source()).isEqualTo("none");
    }
}
