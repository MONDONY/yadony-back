package com.yadony.api.city;

import com.yadony.api.city.dto.CitySearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CityServiceTest {

    @Mock
    private CityRepository cityRepository;

    private CityService cityService;

    @BeforeEach
    void setUp() {
        cityService = new CityService(cityRepository);
    }

    @Test
    void search_returnsMappedResults() {
        CityEntity entity = makeCity(1L, "Dakar", "SN", "Sénégal", 2_500_000L,
            new BigDecimal("14.71"), new BigDecimal("-17.47"));
        when(cityRepository.searchByName("Dak", 10)).thenReturn(List.of(entity));

        List<CitySearchResponse> results = cityService.search("Dak", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).name()).isEqualTo("Dakar");
        assertThat(results.get(0).countryCode()).isEqualTo("SN");
        assertThat(results.get(0).countryName()).isEqualTo("Sénégal");
        assertThat(results.get(0).lat()).isEqualTo(14.71);
        assertThat(results.get(0).lng()).isEqualTo(-17.47);
    }

    @Test
    void search_withNullQuery_throwsIllegalArgument() {
        assertThatThrownBy(() -> cityService.search(null, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("query");
    }

    @Test
    void search_withBlankQuery_throwsIllegalArgument() {
        assertThatThrownBy(() -> cityService.search("", 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("query");
    }

    @Test
    void search_withQueryShorterThanTwo_throwsIllegalArgument() {
        assertThatThrownBy(() -> cityService.search("D", 10))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void search_capsLimitAtFifteen() {
        when(cityRepository.searchByName("Dak", 10)).thenReturn(List.of());
        cityService.search("Dak", 10);
        verify(cityRepository).searchByName("Dak", 10);

        when(cityRepository.searchByName("Dak", 15)).thenReturn(List.of());
        cityService.search("Dak", 50);
        verify(cityRepository).searchByName("Dak", 15);
    }

    @Test
    void search_clampsLimitAtOne() {
        when(cityRepository.searchByName("Dak", 1)).thenReturn(List.of());

        cityService.search("Dak", -10);

        verify(cityRepository).searchByName("Dak", 1);
    }

    private CityEntity makeCity(Long id, String name, String code, String country,
                                 Long pop, BigDecimal lat, BigDecimal lng) {
        CityEntity e = new CityEntity();
        e.setId(id);
        e.setName(name);
        e.setCountryCode(code);
        e.setCountryName(country);
        e.setPopulation(pop);
        e.setLatitude(lat);
        e.setLongitude(lng);
        return e;
    }
}
