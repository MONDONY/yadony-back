package com.yadony.api.search;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fait tourner {@link SearchCityRepository#findSimilar} contre un vrai PostgreSQL
 * (binaire embarqué {@code zonky}, pas Docker), avec le vrai jeu de données GeoNames
 * chargé au démarrage par {@code com.yadony.api.city.GeoNamesDataLoader}.
 *
 * <p>Toute la suite unitaire du module {@code search} ({@code CityLexiconTest},
 * {@code SearchQueryParserCorpusTest}) mocke {@link SearchCityRepository} —
 * volontaire, pour la rapidité et l'isolation — mais cela laissait la seule ligne
 * de code de la branche qui touche réellement la base, la requête native
 * {@code similarity(...)} de {@code pg_trgm}, sans aucun test capable de
 * l'exécuter : le profil {@code test} tourne sur H2, où {@code similarity()}
 * n'existe pas. Trouvaille CRITIQUE (C3) de la revue finale de branche — le
 * 99,1 % JaCoCo du module était trompeur sur ce point précis. Cette classe comble
 * le trou avec le même montage que {@code CucumberSpringContext} (PostgreSQL réel,
 * migrations Flyway appliquées, extension {@code pg_trgm} donc réellement créée).
 */
@SpringBootTest
@ActiveProfiles("e2e")
class SearchCityRepositoryIT {

    static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start embedded PostgreSQL", e);
        }
    }

    @DynamicPropertySource
    static void overrideDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    private SearchCityRepository repository;

    @Test
    void exactMatchAgainstRealData_scoresOneAndSortsFirst() {
        List<SearchCityRepository.CityMatch> matches = repository.findSimilar("bamako", 0.4, 5);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).name()).isEqualTo("Bamako");
        assertThat(matches.get(0).similarity()).isEqualTo(1.0);
    }

    @Test
    void typoAgainstRealData_stillResolvesAboveThreshold() {
        // Même mot que SearchQueryParserCorpusTest#misspelledCityStillResolves, qui
        // suppose (via son approximation locale de similarité) que ce seuil tient.
        // Ce test vérifie que la vraie fonction pg_trgm de PostgreSQL est bien
        // d'accord.
        List<SearchCityRepository.CityMatch> matches = repository.findSimilar("bamacko", 0.4, 5);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).name()).isEqualTo("Bamako");
    }

    @Test
    void unrelatedWord_returnsEmptyBelowThreshold() {
        List<SearchCityRepository.CityMatch> matches = repository.findSimilar("zzzqqqxxx", 0.4, 5);

        assertThat(matches).isEmpty();
    }

    @Test
    void realHomonyms_areOrderedByPopulationDescWhenSimilarityTies() {
        // Vraies lignes GeoNames : plusieurs "Paris" (France, États-Unis, Canada...),
        // toutes à similarité 1.0 pour le token "paris". Le départage-par-nom-
        // identique de CityLexicon (C1) compte sur ce tri SQL par population pour
        // trancher sans poser de question à l'utilisateur.
        List<SearchCityRepository.CityMatch> matches = repository.findSimilar("paris", 0.4, 10);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).name()).isEqualTo("Paris");
        assertThat(matches.get(0).countryCode()).isEqualTo("FR");
        for (int i = 1; i < matches.size(); i++) {
            if (matches.get(i - 1).similarity() == matches.get(i).similarity()) {
                assertThat(matches.get(i - 1).population())
                        .isGreaterThanOrEqualTo(matches.get(i).population());
            }
        }
    }

    @Test
    void hyphenJoinedCompoundName_meetsCityLexiconsPairAcceptThreshold() {
        // Valide contre les vraies données ce que CityLexiconTest suppose avec des
        // mocks : la requête jointe par trait d'union sur un nom composé réel doit
        // dépasser le PAIR_ACCEPT_THRESHOLD (0.6) que CityLexicon utilise pour
        // accepter un essai à deux mots plutôt que de retomber mot à mot.
        List<SearchCityRepository.CityMatch> matches = repository.findSimilar("saint-louis", 0.4, 5);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).name()).isEqualTo("Saint-Louis");
        assertThat(matches.get(0).similarity()).isGreaterThanOrEqualTo(0.6);
    }

    // ---------- I1 : désaccentuation (repli translate(), sans extension unaccent) ----------
    //
    // Vérifié empiriquement contre la vraie base avant le fix : sans repli
    // translate(), « lome » (saisie plausible sans clavier français) faisait
    // remonter "Lomme" (France, 29 892 hab., similarité 0.571) AVANT "Lomé" (Togo,
    // 2 188 376 hab., similarité 0.429) — une ville fausse avec confiance, sans
    // aucun unresolved. « thies » et « segou » ne faisaient même pas apparaître
    // "Thiès" ni "Ségou" dans le top 3. Les trois tests suivants figent le
    // comportement corrigé.

    @Test
    void accentedCity_typedWithoutAccent_stillWinsOverUnrelatedHomograph() {
        List<SearchCityRepository.CityMatch> matches = repository.findSimilar("lome", 0.4, 5);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).name()).isEqualTo("Lomé");
        assertThat(matches.get(0).similarity()).isEqualTo(1.0);
    }

    @Test
    void thies_typedWithoutAccent_resolvesToTheRealSenegaleseCity() {
        List<SearchCityRepository.CityMatch> matches = repository.findSimilar("thies", 0.4, 5);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).name()).isEqualTo("Thiès");
        assertThat(matches.get(0).countryCode()).isEqualTo("SN");
    }

    @Test
    void segou_typedWithoutAccent_resolvesToTheRealMalianCity() {
        List<SearchCityRepository.CityMatch> matches = repository.findSimilar("segou", 0.4, 5);

        assertThat(matches).isNotEmpty();
        assertThat(matches.get(0).name()).isEqualTo("Ségou");
        assertThat(matches.get(0).countryCode()).isEqualTo("ML");
    }

    @Test
    void limitCapsTheNumberOfCandidatesReturned() {
        List<SearchCityRepository.CityMatch> matches = repository.findSimilar("paris", 0.4, 2);

        assertThat(matches).hasSizeLessThanOrEqualTo(2);
    }
}
