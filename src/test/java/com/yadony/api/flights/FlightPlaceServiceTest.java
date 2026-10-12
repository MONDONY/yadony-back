package com.yadony.api.flights;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FlightPlaceServiceTest {

    @Mock TravelpayoutsClient client;
    @Mock FlightCityCodeRepository cityCodes;

    FlightPlaceService service;

    @BeforeEach
    void setUp() {
        service = new FlightPlaceService(client, cityCodes);
        lenient().when(client.isConfigured()).thenReturn(true);
    }

    private static TravelpayoutsPlace city(String code, String name) {
        return new TravelpayoutsPlace("city", code, name, "CI", "Côte d'Ivoire", name);
    }

    @Test
    void suggest_mapsAndCaches() {
        when(client.places("Abid", "en")).thenReturn(List.of(city("abj", "Abidjan"), city("TOOLONG", "X")));

        var first = service.suggest(" Abid ", "en");
        var second = service.suggest("abid", "en");

        assertThat(first).singleElement().satisfies(p -> {
            assertThat(p.code()).isEqualTo("ABJ");
            assertThat(p.name()).isEqualTo("Abidjan");
        });
        assertThat(second).isEqualTo(first);
        verify(client, times(1)).places(anyString(), anyString());
    }

    @Test
    void suggest_shortTerm_returnsNothingWithoutCalling() {
        assertThat(service.suggest("a", "fr")).isEmpty();
        assertThat(service.suggest(null, "fr")).isEmpty();
        verify(client, never()).places(anyString(), anyString());
    }

    @Test
    void suggest_providerDown_is502_andUnconfigured_is503() {
        when(client.places(anyString(), anyString())).thenThrow(new FlightProviderException("down"));
        assertThatThrownBy(() -> service.suggest("Dakar", "fr"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("flights-provider-error");

        when(client.isConfigured()).thenReturn(false);
        assertThatThrownBy(() -> service.suggest("Dakar", "fr"))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting(e -> ((YadonyBusinessException) e).getErrorCode()).isEqualTo("flights-unavailable");
    }

    @Test
    void resolveCity_knownCity_readsTheTable() {
        FlightCityCodeEntity known = new FlightCityCodeEntity();
        known.setIataCode("ABJ");
        when(cityCodes.findByCityKey("abidjan")).thenReturn(Optional.of(known));

        assertThat(service.resolveCity("  Abidjan ")).contains("ABJ");
        verify(client, never()).places(anyString(), anyString());
    }

    @Test
    void resolveCity_knownMiss_isEmptyWithoutCalling() {
        when(cityCodes.findByCityKey("village")).thenReturn(Optional.of(new FlightCityCodeEntity()));

        assertThat(service.resolveCity("Village")).isEmpty();
        verify(client, never()).places(anyString(), anyString());
    }

    @Test
    void resolveCity_newCity_asksTheApiAndRemembers() {
        when(cityCodes.findByCityKey("bamako")).thenReturn(Optional.empty());
        when(client.places("Bamako", "fr")).thenReturn(List.of(city("BKO", "Bamako")));

        assertThat(service.resolveCity("Bamako")).contains("BKO");

        ArgumentCaptor<FlightCityCodeEntity> saved = ArgumentCaptor.forClass(FlightCityCodeEntity.class);
        verify(cityCodes).save(saved.capture());
        assertThat(saved.getValue().getCityKey()).isEqualTo("bamako");
        assertThat(saved.getValue().getIataCode()).isEqualTo("BKO");
    }

    @Test
    void resolveCity_noMatch_remembersTheMiss() {
        when(cityCodes.findByCityKey(anyString())).thenReturn(Optional.empty());
        when(client.places(anyString(), anyString())).thenReturn(List.of());

        assertThat(service.resolveCity("Nulle part")).isEmpty();
        ArgumentCaptor<FlightCityCodeEntity> saved = ArgumentCaptor.forClass(FlightCityCodeEntity.class);
        verify(cityCodes).save(saved.capture());
        assertThat(saved.getValue().getIataCode()).isNull();
    }

    @Test
    void resolveCity_providerDown_remembersNothing() {
        when(cityCodes.findByCityKey(anyString())).thenReturn(Optional.empty());
        when(client.places(anyString(), anyString())).thenThrow(new FlightProviderException("down"));

        assertThat(service.resolveCity("Douala")).isEmpty();
        verify(cityCodes, never()).save(any());
    }

    @Test
    void resolveCity_concurrentInsert_stillReturnsTheCode() {
        when(cityCodes.findByCityKey(anyString())).thenReturn(Optional.empty());
        when(client.places(anyString(), anyString())).thenReturn(List.of(city("DLA", "Douala")));
        when(cityCodes.save(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThat(service.resolveCity("Douala")).contains("DLA");
    }

    @Test
    void resolveCity_blankOrUnconfigured_isEmpty() {
        assertThat(service.resolveCity(" ")).isEmpty();
        when(cityCodes.findByCityKey(anyString())).thenReturn(Optional.empty());
        when(client.isConfigured()).thenReturn(false);
        assertThat(service.resolveCity("Lomé")).isEmpty();
    }

    @Test
    void normalize_removesAccentsCaseAndExtraSpaces() {
        assertThat(FlightPlaceService.normalize("  Lomé   Centre ")).isEqualTo("lome centre");
    }
}
