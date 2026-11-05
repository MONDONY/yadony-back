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

/**
 * Autocomplétion de villes sans accent : « Thies » doit trouver « Thiès ». GeoNames garde
 * les accents en base, la diaspora tape sans clavier français.
 *
 * <p>Villes à nom unique (préfixe aléatoire) : le référentiel GeoNames chargé au démarrage
 * reste hors du jeu, et ses lignes ne sont jamais supprimées.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Recherche de villes insensible aux accents")
class CityRepositoryAccentSearchIntegrationTest {

    @Autowired private CityRepository cityRepository;

    private final List<CityEntity> citiesToClean = new ArrayList<>();

    @AfterEach
    void cleanOwnRows() {
        citiesToClean.forEach(c -> cityRepository.findById(c.getId()).ifPresent(cityRepository::delete));
    }

    @Test
    @DisplayName("une saisie sans accent trouve la ville accentuée")
    void unaccentedQuery_findsAccentedCity() {
        String base = token();
        CityEntity thies = persist(base + "Thiès", "Sénégalx", 320_000L);

        assertThat(cityRepository.searchByName(base + "Thies", 10))
                .extracting(CityEntity::getId).containsExactly(thies.getId());
    }

    @Test
    @DisplayName("une saisie avec accent trouve toujours la ville accentuée")
    void accentedQuery_stillFindsAccentedCity() {
        String base = token();
        CityEntity thies = persist(base + "Thiès", "Sénégalx", 320_000L);

        assertThat(cityRepository.searchByName(base + "Thiès", 10))
                .extracting(CityEntity::getId).containsExactly(thies.getId());
    }

    @Test
    @DisplayName("la casse et les accents se combinent (MAJUSCULES sans accent)")
    void upperCaseWithoutAccent_findsAccentedCity() {
        String base = token();
        CityEntity bouake = persist(base + "Bouaké", "Côte d'Ivoirex", 540_000L);

        assertThat(cityRepository.searchByName((base + "BOUAKE"), 10))
                .extracting(CityEntity::getId).containsExactly(bouake.getId());
    }

    @Test
    @DisplayName("un nom de pays accentué se trouve sans accent")
    void unaccentedQuery_findsCityByAccentedCountry() {
        String country = token() + "Côte";
        CityEntity city = persist(token(), country, 1_000L);

        assertThat(cityRepository.searchByName(country.replace("ô", "o"), 10))
                .extracting(CityEntity::getId).containsExactly(city.getId());
    }

    @Test
    @DisplayName("la saisie peut tomber au milieu d'un nom accentué")
    void unaccentedQuery_matchesInsideAccentedName() {
        String base = token();
        CityEntity city = persist("Ab" + base + "é", "Ailleurs", 10L);

        assertThat(cityRepository.searchByName(base + "e", 10))
                .extracting(CityEntity::getId).containsExactly(city.getId());
    }

    @Test
    @DisplayName("le préfixe accentué passe avant le nom qui contient la saisie")
    void accentedPrefix_ranksBeforeContainingName() {
        String base = token();
        CityEntity startingWith = persist(base + "Lomé", "Ailleurs", 10L);
        CityEntity containing = persist("Wer" + base + "lome", "Ailleurs", 9_000_000L);

        assertThat(cityRepository.searchByName(base + "lome", 10))
                .extracting(CityEntity::getId)
                .containsExactly(startingWith.getId(), containing.getId());
    }

    @Test
    @DisplayName("replier les accents ne crée pas de faux positifs")
    void unaccentedQuery_doesNotMatchOtherCities() {
        String base = token();
        persist(base + "Thika", "Kenyax", 100_000L);

        assertThat(cityRepository.searchByName(base + "Thies", 10)).isEmpty();
    }

    /** Mot unique, sans accent ni chiffre en tête : ni ville ni pays du référentiel ne le contient. */
    private static String token() {
        return "Zqx" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** L'id des villes est assigné (GeoNames) : on en tire un hors de la plage du référentiel. */
    private CityEntity persist(String name, String countryName, long population) {
        CityEntity city = new CityEntity();
        city.setId(9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L));
        city.setName(name);
        city.setCountryCode("NE");
        city.setCountryName(countryName);
        city.setPopulation(population);
        city.setLatitude(new BigDecimal("13.510000"));
        city.setLongitude(new BigDecimal("2.110000"));
        CityEntity saved = cityRepository.save(city);
        citiesToClean.add(saved);
        return saved;
    }
}
