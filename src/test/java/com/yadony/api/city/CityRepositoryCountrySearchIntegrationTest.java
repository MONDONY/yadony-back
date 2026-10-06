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
 * Recherche de villes par nom de pays (Sentry FLUTTER-D3/D7) : « Niger » ne renvoyait que
 * des villes dont le NOM contient « niger » (Wernigerode…), jamais Niamey.
 *
 * <p>Pays et villes fictifs à nom unique : le référentiel GeoNames chargé au démarrage
 * reste hors du jeu, et ses lignes ne sont jamais supprimées.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Recherche de villes par nom de pays")
class CityRepositoryCountrySearchIntegrationTest {

    @Autowired private CityRepository cityRepository;

    private final List<CityEntity> citiesToClean = new ArrayList<>();

    @AfterEach
    void cleanOwnRows() {
        citiesToClean.forEach(c -> cityRepository.findById(c.getId()).ifPresent(cityRepository::delete));
    }

    @Test
    @DisplayName("le nom exact d'un pays renvoie ses villes, les plus peuplées d'abord")
    void exactCountryName_returnsItsCitiesByPopulation() {
        String country = token();
        CityEntity capital = persist(token(), country, 1_300_000L);
        CityEntity smaller = persist(token(), country, 200_000L);

        List<CityEntity> found = cityRepository.searchByName(country, 10);

        assertThat(found).extracting(CityEntity::getId)
                .containsExactly(capital.getId(), smaller.getId());
    }

    @Test
    @DisplayName("pays exact avant pays préfixe, même moins peuplé (Niger avant Nigeria)")
    void exactCountry_beforeLongerCountrySharingThePrefix() {
        String niger = token();
        CityEntity niamey = persist(token(), niger, 1_300_000L);
        CityEntity lagos = persist(token(), niger + "ia", 15_000_000L);

        List<CityEntity> found = cityRepository.searchByName(niger, 10);

        assertThat(found).extracting(CityEntity::getId)
                .containsExactly(niamey.getId(), lagos.getId());
    }

    @Test
    @DisplayName("un nom de ville qui commence par la saisie reste premier ; le nom qui la "
            + "contient passe après le pays exact")
    void cityNameRanks_aroundTheExactCountry() {
        String query = token();
        CityEntity cityStartingWith = persist(query + "ville", "Ailleurs", 10L);
        CityEntity cityContaining = persist("Wer" + query + "ode", "Ailleurs", 30_000L);
        CityEntity countryCity = persist(token(), query, 1_000_000L);

        List<CityEntity> found = cityRepository.searchByName(query.toLowerCase(), 10);

        assertThat(found).extracting(CityEntity::getId)
                .containsExactly(cityStartingWith.getId(), countryCity.getId(), cityContaining.getId());
    }

    @Test
    @DisplayName("la limite s'applique après le classement")
    void limit_appliesAfterRanking() {
        String country = token();
        CityEntity biggest = persist(token(), country, 900L);
        persist(token(), country, 100L);

        assertThat(cityRepository.searchByName(country, 1))
                .extracting(CityEntity::getId).containsExactly(biggest.getId());
    }

    /** Mot unique, sans chiffre en tête : ni ville ni pays du référentiel ne le contient. */
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
