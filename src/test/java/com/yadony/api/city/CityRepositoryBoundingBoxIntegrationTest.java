package com.yadony.api.city;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/** Requêtes du flou de position. Villes fictives en Antarctique, hors du référentiel GeoNames. */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("CityRepository : rectangle et lookup par nom + pays")
class CityRepositoryBoundingBoxIntegrationTest {

    @Autowired private CityRepository cityRepository;
    private final List<CityEntity> toClean = new ArrayList<>();

    @AfterEach
    void clean() {
        toClean.forEach(c -> cityRepository.findById(c.getId()).ifPresent(cityRepository::delete));
    }

    @Test
    @DisplayName("le rectangle respecte la population minimale et les bornes")
    void boundingBoxFiltersByPopulationAndBounds() {
        CityEntity big = persist("Zqx" + UUID.randomUUID(), -80.0, 100.0, 50_000L);
        persist("Zqx" + UUID.randomUUID(), -80.0, 100.1, 500L);
        persist("Zqx" + UUID.randomUUID(), -70.0, 100.0, 50_000L);

        List<CityEntity> found = cityRepository.findInBoundingBox(-80.5, -79.5, 99.5, 100.5, 20_000L, 10);

        assertThat(found).extracting(CityEntity::getId).containsExactly(big.getId());
    }

    @Test
    @DisplayName("le lookup par nom ignore la casse et exige le code pays")
    void lookupByNameIsCaseInsensitiveAndCountryScoped() {
        String name = "Zqx" + UUID.randomUUID().toString().substring(0, 8);
        CityEntity c = persist(name, -81.0, 101.0, 1_000L);

        assertThat(cityRepository.findTopByNameAndCountryCode(name.toUpperCase(), "AQ"))
                .map(CityEntity::getId).contains(c.getId());
        assertThat(cityRepository.findTopByNameAndCountryCode(name, "FR")).isEmpty();
    }

    private CityEntity persist(String name, double lat, double lon, long population) {
        CityEntity city = new CityEntity();
        city.setId(9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L));
        city.setName(name);
        city.setCountryCode("AQ");
        city.setCountryName("Antarctique");
        city.setPopulation(population);
        city.setLatitude(BigDecimal.valueOf(lat));
        city.setLongitude(BigDecimal.valueOf(lon));
        CityEntity saved = cityRepository.save(city);
        toClean.add(saved);
        return saved;
    }
}
