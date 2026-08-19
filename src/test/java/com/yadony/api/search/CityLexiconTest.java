package com.yadony.api.search;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CityLexiconTest {

    @Mock
    private SearchCityRepository repository;

    private CityLexicon lexicon;

    @BeforeEach
    void setUp() {
        lexicon = new CityLexicon(repository);
    }

    private static ParseState state(String text) {
        return new ParseState(SearchTokenizer.tokenize(text), SearchMode.TRIPS,
                LocalDate.of(2026, 8, 19));
    }

    @Test
    void cityAfterToPreposition_becomesArrival() {
        when(repository.findSimilar(eq("bamako"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Bamako", 1.0, 2_700_000L)));

        ParseState s = state("à Bamako");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bamako");
    }

    @Test
    void cityAfterFromPreposition_becomesDeparture() {
        when(repository.findSimilar(eq("lyon"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Lyon", 1.0, 500_000L)));

        ParseState s = state("depuis Lyon");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("departureCity", "Lyon");
    }

    @Test
    void loneCityWithoutPreposition_becomesArrival() {
        // Cas dominant : l'expéditeur nomme la destination, pas son propre départ.
        when(repository.findSimilar(eq("dakar"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Dakar", 1.0, 1_100_000L)));

        ParseState s = state("Dakar");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Dakar");
        assertThat(s.values()).doesNotContainKey("departureCity");
    }

    @Test
    void twoCitiesWithoutPreposition_readAsDepartureThenArrival() {
        when(repository.findSimilar(eq("paris"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Paris", 1.0, 2_100_000L)));
        when(repository.findSimilar(eq("dakar"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Dakar", 1.0, 1_100_000L)));

        ParseState s = state("Paris Dakar");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("departureCity", "Paris");
        assertThat(s.values()).containsEntry("arrivalCity", "Dakar");
    }

    @Test
    void misspelledCityStillResolves() {
        when(repository.findSimilar(eq("bamacko"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Bamako", 0.72, 2_700_000L)));

        ParseState s = state("à Bamacko");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bamako");
    }

    @Test
    void twoCloseCandidates_produceAnAmbiguityInsteadOfAGuess() {
        when(repository.findSimilar(eq("kolda"), anyDouble(), anyInt()))
            .thenReturn(List.of(
                new SearchCityRepository.CityMatch("Kolda", 0.80, 60_000L),
                new SearchCityRepository.CityMatch("Koldo", 0.78, 40_000L)));

        ParseState s = state("à Kolda");
        lexicon.apply(s);

        assertThat(s.values()).doesNotContainKey("arrivalCity");
        assertThat(s.unresolved()).hasSize(1);
        assertThat(s.unresolved().get(0).kind()).isEqualTo(UnresolvedKind.CITY_AMBIGUOUS);
        assertThat(s.unresolved().get(0).options()).containsExactly("Kolda", "Koldo");
    }

    @Test
    void noCandidateAboveThreshold_producesAnUnknownCity() {
        when(repository.findSimilar(anyString(), anyDouble(), anyInt())).thenReturn(List.of());

        ParseState s = state("à Zzzzzz");
        lexicon.apply(s);

        assertThat(s.values()).isEmpty();
        assertThat(s.unresolved()).hasSize(1);
        assertThat(s.unresolved().get(0).kind()).isEqualTo(UnresolvedKind.CITY_UNKNOWN);
    }

    @Test
    void unknownWordWithoutPrecedingPreposition_isSilentlyIgnored() {
        // Règle produit tranchée : un mot inconnu n'est signalé que s'il suit une
        // préposition de lieu. Sans préposition devant, chaque mot non reconnu de la
        // phrase remonterait comme ville introuvable et noierait l'utilisateur.
        when(repository.findSimilar(anyString(), anyDouble(), anyInt())).thenReturn(List.of());

        ParseState s = state("Zzzzzz");
        lexicon.apply(s);

        assertThat(s.values()).isEmpty();
        assertThat(s.unresolved()).isEmpty();
    }

    @Test
    void alreadyConsumedTokensAreNeverLookedUp() {
        // « mars » a été consommé par la passe dates : aucune requête ne part.
        ParseState s = state("mars");
        s.consume(0, 1);

        lexicon.apply(s);

        assertThat(s.values()).isEmpty();
        assertThat(s.unresolved()).isEmpty();
        // Le point du test n'est pas seulement l'absence de filtre posé, mais
        // l'absence de requête trigramme sur un token déjà réclamé par une passe
        // précédente : on le vérifie directement sur le mock.
        verifyNoInteractions(repository);
    }

    @Test
    void arrivalSetByPrepositionIsNotOverwrittenByTwoTrailingFreeCities() {
        // Régression : « à Bamako » doit rester l'arrivée même quand deux villes
        // libres suivent ensuite dans la phrase. L'heuristique « deux villes
        // libres = départ puis arrivée » ne doit jamais écraser une direction
        // déjà fixée explicitement par une préposition.
        when(repository.findSimilar(eq("bamako"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Bamako", 1.0, 2_700_000L)));
        when(repository.findSimilar(eq("paris"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Paris", 1.0, 2_100_000L)));
        when(repository.findSimilar(eq("lyon"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Lyon", 1.0, 500_000L)));

        ParseState s = state("à Bamako Paris Lyon");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bamako");
    }

    @Test
    void singleFreeCityBecomesDepartureWhenArrivalAlreadySetByPreposition() {
        // « à Bamako » fixe l'arrivée explicitement ; la seule ville libre restante
        // ne peut alors plus être lue comme une deuxième arrivée, donc elle devient
        // le départ.
        when(repository.findSimilar(eq("bamako"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Bamako", 1.0, 2_700_000L)));
        when(repository.findSimilar(eq("paris"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Paris", 1.0, 2_100_000L)));

        ParseState s = state("à Bamako Paris");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bamako");
        assertThat(s.values()).containsEntry("departureCity", "Paris");
    }
}
