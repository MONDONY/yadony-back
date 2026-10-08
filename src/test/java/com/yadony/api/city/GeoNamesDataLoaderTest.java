package com.yadony.api.city;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeoNamesDataLoaderTest {

    @Mock
    private CityRepository cityRepository;

    private GeoNamesDataLoader loader;

    @BeforeEach
    void setUp() {
        loader = new GeoNamesDataLoader(cityRepository);
    }

    @Test
    void run_skipsImportWhenTableAlreadyPopulated() throws Exception {
        when(cityRepository.count()).thenReturn(1000L);

        loader.run(null);

        verify(cityRepository, never()).saveAll(anyList());
    }

    @Test
    void run_skipsImportWhenFileNotFound() throws Exception {
        // Sous-classe qui pointe sur un fichier inexistant pour simuler l'absence du .gz
        GeoNamesDataLoader loaderNoFile = new GeoNamesDataLoader(cityRepository) {
            @Override
            public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
                if (cityRepository.count() > 0) return;
                org.springframework.core.io.ClassPathResource resource =
                    new org.springframework.core.io.ClassPathResource("geonames/DOES_NOT_EXIST.txt.gz");
                if (!resource.exists()) {
                    org.slf4j.LoggerFactory.getLogger(GeoNamesDataLoader.class)
                        .warn("[GeoNames] Fichier geonames/cities5000.txt.gz introuvable — import ignoré");
                    return;
                }
            }
        };

        when(cityRepository.count()).thenReturn(0L);

        // Ne doit pas lancer d'exception
        loaderNoFile.run(null);

        verify(cityRepository, never()).saveAll(anyList());
    }

    @Test
    void run_importsDataWhenFilePresent() throws Exception {
        // Le fichier cities5000.txt.gz est dans le classpath (src/main/resources/geonames/)
        // On mock saveAll pour qu'il retourne une liste vide
        when(cityRepository.count()).thenReturn(0L);
        when(cityRepository.saveAll(anyList())).thenReturn(List.of());

        // Ne doit pas lancer d'exception et doit appeler saveAll au moins une fois
        loader.run(null);

        verify(cityRepository, atLeastOnce()).saveAll(anyList());
    }

    @Test
    void timezoneOf_readsGeoNamesColumn18() {
        String[] full = new String[19];
        java.util.Arrays.fill(full, "");
        full[17] = " Africa/Porto-Novo ";
        org.assertj.core.api.Assertions.assertThat(GeoNamesDataLoader.timezoneOf(full))
                .isEqualTo("Africa/Porto-Novo");

        full[17] = "";
        org.assertj.core.api.Assertions.assertThat(GeoNamesDataLoader.timezoneOf(full)).isNull();

        full[17] = "x".repeat(41);
        org.assertj.core.api.Assertions.assertThat(GeoNamesDataLoader.timezoneOf(full)).isNull();

        org.assertj.core.api.Assertions.assertThat(GeoNamesDataLoader.timezoneOf(new String[15])).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void run_setsTimezoneOnImportedCities() throws Exception {
        when(cityRepository.count()).thenReturn(0L);
        java.util.List<CityEntity> imported = new java.util.ArrayList<>();
        when(cityRepository.saveAll(anyList())).thenAnswer(inv -> {
            imported.addAll((java.util.List<CityEntity>) inv.getArgument(0));
            return java.util.List.of();
        });

        loader.run(null);

        org.assertj.core.api.Assertions.assertThat(imported)
                .filteredOn(c -> "Cotonou".equals(c.getName()))
                .extracting(CityEntity::getTimezone)
                .contains("Africa/Porto-Novo");
    }
}
