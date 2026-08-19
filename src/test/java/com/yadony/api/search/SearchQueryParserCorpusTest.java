package com.yadony.api.search;

import com.yadony.api.search.dto.SearchParseResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Le corpus de référence du parseur.
 *
 * <p>Les villes sont servies par un dictionnaire en dur plutôt que par la base : ce
 * test doit tourner sans PostgreSQL, et le profil de test désactive Flyway, donc la
 * table {@code cities} y est vide de toute façon.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SearchQueryParserCorpusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);

    private static final List<String> KNOWN_CITIES =
            List.of("Paris", "Lyon", "Marseille", "Dakar", "Bamako", "Abidjan",
                    "Douala", "Kolda", "Ziguinchor", "Nkongsamba", "Kédougou", "Mars");

    @Mock
    private SearchCityRepository cityRepository;

    private SearchQueryParser parser;

    @BeforeEach
    void setUp() {
        when(cityRepository.findSimilar(anyString(), anyDouble(), anyInt()))
            .thenAnswer(invocation -> {
                String token = invocation.getArgument(0);
                return KNOWN_CITIES.stream()
                    .map(city -> new SearchCityRepository.CityMatch(
                        city, similarity(SearchTokenizer.normalize(city), token), 1_000_000L))
                    .filter(m -> m.similarity() >= 0.4)
                    .sorted((a, b) -> Double.compare(b.similarity(), a.similarity()))
                    .toList();
            });
        parser = new SearchQueryParser(new CityLexicon(cityRepository));
    }

    /** Similarité de trigrammes, approximation suffisante pour les tests. */
    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        var ta = trigrams(a);
        var tb = trigrams(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        long shared = ta.stream().filter(tb::contains).count();
        return (double) shared / (ta.size() + tb.size() - shared);
    }

    private static java.util.Set<String> trigrams(String s) {
        String padded = "  " + s.toLowerCase(Locale.ROOT) + " ";
        var out = new java.util.HashSet<String>();
        for (int i = 0; i + 3 <= padded.length(); i++) out.add(padded.substring(i, i + 3));
        return out;
    }

    private SearchParseResponse parse(String text) {
        return parser.parse(text, SearchMode.TRIPS, TODAY);
    }

    // ---------- cas nominal ----------

    @Test
    void fullSentence_fillsEveryExpectedFilter() {
        SearchParseResponse r = parse("20 kilos à Bamako en mars");

        assertThat(r.filters().arrivalCity()).isEqualTo("Bamako");
        assertThat(r.filters().minAvailableKg()).isEqualByComparingTo("20");
        assertThat(r.filters().departureDateFrom()).isEqualTo(LocalDate.of(2027, 3, 1));
        assertThat(r.filters().departureDateTo()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(r.unresolved()).isEmpty();
    }

    @Test
    void corridorWithBothPrepositions() {
        SearchParseResponse r = parse("de Paris à Dakar");

        assertThat(r.filters().departureCity()).isEqualTo("Paris");
        assertThat(r.filters().arrivalCity()).isEqualTo("Dakar");
    }

    @Test
    void bareCorridor_readsAsDepartureThenArrival() {
        SearchParseResponse r = parse("Paris Dakar");

        assertThat(r.filters().departureCity()).isEqualTo("Paris");
        assertThat(r.filters().arrivalCity()).isEqualTo("Dakar");
    }

    @Test
    void intentNoiseIsStripped() {
        SearchParseResponse r = parse("je veux envoyer un colis à Abidjan");

        assertThat(r.filters().arrivalCity()).isEqualTo("Abidjan");
    }

    // ---------- le piège du mois pris pour une ville ----------

    @Test
    void monthIsNeverMistakenForACity() {
        SearchParseResponse r = parse("à Dakar en mars");

        assertThat(r.filters().arrivalCity()).isEqualTo("Dakar");
        assertThat(r.filters().departureCity()).isNull();
        assertThat(r.filters().departureDateFrom()).isEqualTo(LocalDate.of(2027, 3, 1));
    }

    // ---------- fautes de frappe et sorties de dictée ----------

    @Test
    void misspelledCityStillResolves() {
        SearchParseResponse r = parse("à Bamacko");

        assertThat(r.filters().arrivalCity()).isEqualTo("Bamako");
    }

    @Test
    void africanToponymFromSpeechRecognition() {
        SearchParseResponse r = parse("à Ziguinchore");

        assertThat(r.filters().arrivalCity()).isEqualTo("Ziguinchor");
    }

    @Test
    void accentlessCityStillResolves() {
        SearchParseResponse r = parse("à Kedougou");

        assertThat(r.filters().arrivalCity()).isEqualTo("Kédougou");
    }

    // ---------- modes ----------

    @Test
    void weightMeansRequiredCapacityInTripsMode() {
        SearchParseResponse r = parser.parse("20 kilos", SearchMode.TRIPS, TODAY);

        assertThat(r.filters().minAvailableKg()).isEqualByComparingTo("20");
        assertThat(r.filters().maxWeight()).isNull();
    }

    @Test
    void weightMeansOfferedCapacityInPackagesMode() {
        SearchParseResponse r = parser.parse("20 kilos", SearchMode.PACKAGES, TODAY);

        assertThat(r.filters().maxWeight()).isEqualByComparingTo("20");
        assertThat(r.filters().minAvailableKg()).isNull();
    }

    // ---------- combinaisons ----------

    @Test
    void flagsAndPriceCombine() {
        SearchParseResponse r = parse("urgent à Douala moins de 8 euros vérifié");

        assertThat(r.filters().arrivalCity()).isEqualTo("Douala");
        assertThat(r.filters().urgent()).isTrue();
        assertThat(r.filters().maxPricePerKg()).isEqualByComparingTo("8");
        assertThat(r.filters().kycVerifiedOnly()).isTrue();
    }

    @Test
    void contentCategoryCombinesWithCorridor() {
        SearchParseResponse r = parse("vêtements pour Dakar");

        assertThat(r.filters().contentType()).isEqualTo("Vêtements & tissus");
        assertThat(r.filters().arrivalCity()).isEqualTo("Dakar");
    }

    // ---------- ambiguïtés ----------

    @Test
    void vaguePriceIsAskedNotGuessed() {
        SearchParseResponse r = parse("à Kolda pas trop cher");

        assertThat(r.filters().arrivalCity()).isEqualTo("Kolda");
        assertThat(r.filters().maxPricePerKg()).isNull();
        assertThat(r.unresolved()).extracting(u -> u.kind())
            .containsExactly(UnresolvedKind.PRICE_VAGUE);
    }

    @Test
    void sentenceWithNothingUsable_producesNoFilterAndNoCrash() {
        SearchParseResponse r = parse("colis pour ma mère au village");

        assertThat(r.filters().arrivalCity()).isNull();
        assertThat(r.filters().departureCity()).isNull();
        assertThat(r.filters().minAvailableKg()).isNull();
    }

    @Test
    void emptyText_returnsAnEmptyResultWithoutThrowing() {
        SearchParseResponse r = parse("");

        assertThat(r.filters().arrivalCity()).isNull();
        assertThat(r.recognized()).isEmpty();
        assertThat(r.unresolved()).isEmpty();
    }

    // ---------- nombres écrits en lettres ----------

    @Test
    void spelledOutWeightResolves() {
        SearchParseResponse r = parse("vingt kilos pour Bamako");

        assertThat(r.filters().minAvailableKg()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(r.filters().arrivalCity()).isEqualTo("Bamako");
    }
}
