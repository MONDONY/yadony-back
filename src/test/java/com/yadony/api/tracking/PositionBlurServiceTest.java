package com.yadony.api.tracking;

import com.yadony.api.city.CityEntity;
import com.yadony.api.city.CityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PositionBlurService")
class PositionBlurServiceTest {

    private static final CityEntity PARIS = city("Paris", "FR", 2_100_000, 48.8566, 2.3522);
    private static final CityEntity LYON = city("Lyon", "FR", 500_000, 45.7640, 4.8357);
    private static final CityEntity DAKAR = city("Dakar", "SN", 1_100_000, 14.6937, -17.4441);
    private static final CityEntity CONAKRY = city("Conakry", "GN", 1_600_000, 9.5370, -13.6785);
    private static final CityEntity JOHANNESBURG = city("Johannesburg", "ZA", 950_000, -26.2041, 28.0473);

    private CityRepository repo;
    private PositionBlurService service;

    @BeforeEach
    void setUp() {
        repo = mock(CityRepository.class);
        List<CityEntity> all = List.of(PARIS, LYON, DAKAR, CONAKRY, JOHANNESBURG);
        when(repo.findTopByNameAndCountryCode(anyString(), anyString())).thenAnswer(inv -> all.stream()
                .filter(c -> c.getName().equalsIgnoreCase(inv.getArgument(0))
                        && c.getCountryCode().equals(inv.getArgument(1)))
                .findFirst());
        when(repo.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong(), anyInt()))
                .thenAnswer(inv -> all.stream()
                        .filter(c -> c.getLatitude().doubleValue() >= (double) inv.getArgument(0)
                                && c.getLatitude().doubleValue() <= (double) inv.getArgument(1)
                                && c.getLongitude().doubleValue() >= (double) inv.getArgument(2)
                                && c.getLongitude().doubleValue() <= (double) inv.getArgument(3))
                        .toList());
        service = new PositionBlurService(repo);
    }

    @Test
    @DisplayName("Lyon sur Paris-Dakar : ville la plus proche Lyon, avancement entre 0 et 100")
    void lyonOnParisDakarGivesLyonAndMidProgress() {
        var r = service.blur(45.80, 4.90, "Paris", "FR", "Dakar", "SN");
        assertThat(r.cityName()).isEqualTo("Lyon");
        assertThat(r.countryCode()).isEqualTo("FR");
        assertThat(r.progressPct()).isNotNull().isGreaterThan(0).isLessThan(100);
    }

    @Test
    @DisplayName("au-delà de l'arrivée, l'avancement est borné à 100")
    void positionBeyondArrivalIsClampedTo100() {
        var r = service.blur(9.6, -13.7, "Paris", "FR", "Dakar", "SN");
        assertThat(r.cityName()).isEqualTo("Conakry");
        assertThat(r.progressPct()).isEqualTo(100);
    }

    @Test
    @DisplayName("loin de la route, le pourcentage est masqué mais la ville reste")
    void positionFarFromRouteHidesPercentage() {
        var r = service.blur(-26.2, 28.0, "Paris", "FR", "Dakar", "SN");
        assertThat(r.cityName()).isEqualTo("Johannesburg");
        assertThat(r.progressPct()).isNull();
    }

    @Test
    @DisplayName("ville de départ ou d'arrivée introuvable : ville seule")
    void missingCityCoordinatesGivesCityOnly() {
        var r = service.blur(45.80, 4.90, "Inconnueville", "FR", "Dakar", "SN");
        assertThat(r.cityName()).isEqualTo("Lyon");
        assertThat(r.progressPct()).isNull();
        when(repo.findTopByNameAndCountryCode(eq("Dakar"), anyString())).thenReturn(Optional.empty());
        assertThat(service.blur(45.80, 4.90, "Paris", "FR", "Dakar", "SN").progressPct()).isNull();
    }

    @Test
    @DisplayName("aucune ville trouvée : ville et pays null")
    void noCityFoundGivesNullCity() {
        when(repo.findInBoundingBox(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong(), anyInt()))
                .thenReturn(List.of());
        var r = service.blur(45.80, 4.90, "Paris", "FR", "Dakar", "SN");
        assertThat(r.cityName()).isNull();
        assertThat(r.countryCode()).isNull();
    }

    @Test
    @DisplayName("paramètres de trajet nuls : pas d'exception, avancement null")
    void nullRouteParamsGiveNullProgress() {
        assertThat(service.blur(45.80, 4.90, null, "FR", "Dakar", "SN").progressPct()).isNull();
        assertThat(service.blur(45.80, 4.90, "Paris", "FR", null, null).progressPct()).isNull();
        assertThat(service.blur(45.80, 4.90, null, null, null, null).cityName()).isEqualTo("Lyon");
    }

    @Test
    @DisplayName("le résultat n'expose que nom, pays et pourcentage")
    void resultNeverContainsRawCoordinates() {
        var components = Arrays.stream(PositionBlurService.BlurredPosition.class.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertThat(components).containsExactly("cityName", "countryCode", "progressPct");
        var r = service.blur(45.80123, 4.90456, "Paris", "FR", "Dakar", "SN");
        assertThat(r.toString()).doesNotContain("45.80").doesNotContain("4.90");
    }

    private static CityEntity city(String name, String cc, long pop, double lat, double lon) {
        CityEntity c = new CityEntity();
        c.setName(name);
        c.setCountryCode(cc);
        c.setCountryName(cc);
        c.setPopulation(pop);
        c.setLatitude(BigDecimal.valueOf(lat));
        c.setLongitude(BigDecimal.valueOf(lon));
        return c;
    }
}
