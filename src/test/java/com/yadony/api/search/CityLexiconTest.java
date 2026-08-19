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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
        // Repli par défaut : l'essai à deux mots (nouveau, tenté avant chaque mot
        // seul) interroge des combinaisons qu'aucun test individuel ne prévoit de
        // stubber explicitement. Sans ce défaut, chaque test devrait mocker la paire
        // en plus du mot seul rien que pour ne pas planter sur un retour null.
        // `lenient` : ce défaut n'est pas forcément consommé par tous les tests
        // (ex: ceux à un seul token, où aucune paire n'est jamais tentée).
        lenient().when(repository.findSimilar(anyString(), anyDouble(), anyInt())).thenReturn(List.of());
    }

    private static ParseState state(String text) {
        return new ParseState(SearchTokenizer.tokenize(text), SearchMode.TRIPS,
                LocalDate.of(2026, 8, 19));
    }

    @Test
    void cityAfterToPreposition_becomesArrival() {
        when(repository.findSimilar(eq("bamako"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Bamako", 1.0, 2_700_000L, "ML")));

        ParseState s = state("à Bamako");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bamako");
    }

    @Test
    void cityAfterFromPreposition_becomesDeparture() {
        when(repository.findSimilar(eq("lyon"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Lyon", 1.0, 500_000L, "FR")));

        ParseState s = state("depuis Lyon");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("departureCity", "Lyon");
    }

    @Test
    void loneCityWithoutPreposition_becomesArrival() {
        // Cas dominant : l'expéditeur nomme la destination, pas son propre départ.
        when(repository.findSimilar(eq("dakar"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Dakar", 1.0, 1_100_000L, "SN")));

        ParseState s = state("Dakar");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Dakar");
        assertThat(s.values()).doesNotContainKey("departureCity");
    }

    @Test
    void twoCitiesWithoutPreposition_readAsDepartureThenArrival() {
        when(repository.findSimilar(eq("paris"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Paris", 1.0, 2_100_000L, "FR")));
        when(repository.findSimilar(eq("dakar"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Dakar", 1.0, 1_100_000L, "SN")));

        ParseState s = state("Paris Dakar");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("departureCity", "Paris");
        assertThat(s.values()).containsEntry("arrivalCity", "Dakar");
    }

    @Test
    void misspelledCityStillResolves() {
        when(repository.findSimilar(eq("bamacko"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Bamako", 0.72, 2_700_000L, "ML")));

        ParseState s = state("à Bamacko");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bamako");
    }

    @Test
    void twoCloseCandidates_produceAnAmbiguityInsteadOfAGuess() {
        when(repository.findSimilar(eq("kolda"), anyDouble(), anyInt()))
            .thenReturn(List.of(
                new SearchCityRepository.CityMatch("Kolda", 0.80, 60_000L, "SN"),
                new SearchCityRepository.CityMatch("Koldo", 0.78, 40_000L, "XX")));

        ParseState s = state("à Kolda");
        lexicon.apply(s);

        assertThat(s.values()).doesNotContainKey("arrivalCity");
        assertThat(s.unresolved()).hasSize(1);
        assertThat(s.unresolved().get(0).kind()).isEqualTo(UnresolvedKind.CITY_AMBIGUOUS);
        // Format documenté dans CityLexicon#distinguishableOptions : "Nom (CC)". Une
        // simple liste de noms ("Paris","Paris","Paris") serait inexploitable côté
        // client dès que deux candidats portent le même nom dans des pays différents.
        assertThat(s.unresolved().get(0).options()).containsExactly("Kolda (SN)", "Koldo (XX)");
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
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Bamako", 1.0, 2_700_000L, "ML")));
        when(repository.findSimilar(eq("paris"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Paris", 1.0, 2_100_000L, "FR")));
        when(repository.findSimilar(eq("lyon"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Lyon", 1.0, 500_000L, "FR")));

        ParseState s = state("à Bamako Paris Lyon");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bamako");
        assertThat(s.values()).doesNotContainKey("departureCity");
        // Paris et Lyon sont bien reconnus (ce ne sont pas des erreurs), mais ni
        // départ ni arrivée n'est libre pour eux : ils ne doivent pas être perdus en
        // silence, le client doit pouvoir demander ce qu'ils signifient.
        assertThat(s.unresolved()).hasSize(2);
        assertThat(s.unresolved()).extracting(u -> u.kind())
            .containsExactly(UnresolvedKind.CITY_UNASSIGNED, UnresolvedKind.CITY_UNASSIGNED);
        assertThat(s.unresolved()).extracting(u -> u.phrase())
            .containsExactly("Paris", "Lyon");
    }

    @Test
    void singleFreeCityBecomesDepartureWhenArrivalAlreadySetByPreposition() {
        // « à Bamako » fixe l'arrivée explicitement ; la seule ville libre restante
        // ne peut alors plus être lue comme une deuxième arrivée, donc elle devient
        // le départ.
        when(repository.findSimilar(eq("bamako"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Bamako", 1.0, 2_700_000L, "ML")));
        when(repository.findSimilar(eq("paris"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Paris", 1.0, 2_100_000L, "FR")));

        ParseState s = state("à Bamako Paris");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bamako");
        assertThat(s.values()).containsEntry("departureCity", "Paris");
        assertThat(s.unresolved()).isEmpty();
    }

    @Test
    void extraFreeCityWhenBothDirectionsAlreadySet_becomesUnresolvedInsteadOfSilentlyDropped() {
        // « à Bamako » et « depuis Dakar » fixent les deux champs explicitement ;
        // « Lyon » libre en trop n'a plus nulle part où aller. Avant cette vague, il
        // disparaissait silencieusement.
        when(repository.findSimilar(eq("bamako"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Bamako", 1.0, 2_700_000L, "ML")));
        when(repository.findSimilar(eq("dakar"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Dakar", 1.0, 1_100_000L, "SN")));
        when(repository.findSimilar(eq("lyon"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Lyon", 1.0, 500_000L, "FR")));

        ParseState s = state("à Bamako depuis Dakar Lyon");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bamako");
        assertThat(s.values()).containsEntry("departureCity", "Dakar");
        assertThat(s.unresolved()).hasSize(1);
        assertThat(s.unresolved().get(0).kind()).isEqualTo(UnresolvedKind.CITY_UNASSIGNED);
        assertThat(s.unresolved().get(0).phrase()).isEqualTo("Lyon");
    }

    // ---------- C1 : homonymie à similarité identique (départage par population) ----------

    @Test
    void sameNameHomonymsAtIdenticalSimilarity_resolveByPopulationInsteadOfAmbiguity() {
        // Données réelles (table cities) : 7 lignes nommées exactement « Paris »
        // (France, États-Unis x5, Canada), toutes à similarité 1.0 pour le token
        // "paris". Paris est le corridor principal du produit : il ne doit jamais
        // remonter comme ambigu. Le tri par population, déjà fait en SQL, désigne
        // Paris France (2 138 551 habitants) en premier.
        when(repository.findSimilar(eq("paris"), anyDouble(), anyInt())).thenReturn(List.of(
            new SearchCityRepository.CityMatch("Paris", 1.0, 2_138_551L, "FR"),
            new SearchCityRepository.CityMatch("Paris", 1.0, 24_782L, "US"),
            new SearchCityRepository.CityMatch("Paris", 1.0, 12_310L, "CA"),
            new SearchCityRepository.CityMatch("Paris", 1.0, 10_150L, "US"),
            new SearchCityRepository.CityMatch("Paris", 1.0, 9_870L, "US")));

        ParseState s = state("à Paris");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Paris");
        assertThat(s.unresolved()).isEmpty();
    }

    @Test
    void sameNameHomonymsAcrossAfricanCorridor_alsoResolveByPopulation() {
        // Même piège côté Afrique : « Kayes » existe au Mali (194 716 hab.) et au
        // Congo (58 737 hab.), toutes deux à similarité 1.0. Le corridor Mali est
        // largement dominant et doit gagner sans poser de question.
        when(repository.findSimilar(eq("kayes"), anyDouble(), anyInt())).thenReturn(List.of(
            new SearchCityRepository.CityMatch("Kayes", 1.0, 194_716L, "ML"),
            new SearchCityRepository.CityMatch("Kayes", 1.0, 58_737L, "CG")));

        ParseState s = state("à Kayes");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Kayes");
        assertThat(s.unresolved()).isEmpty();
    }

    // ---------- borne de performance : au plus 8 requêtes trigramme par appel ----------

    @Test
    void lookupsAreCappedAtEightPerRequest() {
        // Chaque token peut désormais coûter jusqu'à deux requêtes (essai à deux mots
        // puis repli à un mot) : la borne a doublé en conséquence (4 → 8). 6 tokens
        // éligibles ici épuisent le budget avant "à Quux", qui n'est donc jamais
        // examiné même si "à" l'aurait rendu éligible à un CITY_UNKNOWN.
        ParseState s = state("Foo Bar Baz Qux Wobble Flerp à Quux");
        lexicon.apply(s);

        verify(repository, times(8)).findSimilar(anyString(), anyDouble(), anyInt());
        verify(repository, never()).findSimilar(eq("quux"), anyDouble(), anyInt());
        // Aucun unresolved pour un token non examiné, même précédé d'une préposition.
        assertThat(s.unresolved()).isEmpty();
    }

    // ---------- C2 : déduplication des résolutions identiques ----------

    @Test
    void twoTokensResolvingToTheSameCity_areNotReadAsTwoDistinctCities() {
        // Deux tokens différents qui convergent, par coïncidence trigramme, vers la
        // même ville ne doivent jamais être lus comme « départ » puis « arrivée ».
        when(repository.findSimilar(eq("norda"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Dakar", 0.5, 1_100_000L, "SN")));
        when(repository.findSimilar(eq("sud"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Dakar", 0.45, 1_100_000L, "SN")));

        ParseState s = state("Norda Sud");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Dakar");
        assertThat(s.values()).doesNotContainKey("departureCity");
        assertThat(s.unresolved()).isEmpty();
    }

    @Test
    void dedupPrefersDirectedResolutionOverFreeDuplicateOfTheSameName() {
        // « à Dakar » fixe la direction explicitement ; un second token qui
        // reconverge (sans préposition) vers le même nom ne doit ni écraser cette
        // direction, ni être lu comme une seconde ville libre.
        when(repository.findSimilar(eq("dakar"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Dakar", 1.0, 1_100_000L, "SN")));
        when(repository.findSimilar(eq("ouest"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Dakar", 0.4, 1_100_000L, "SN")));

        ParseState s = state("à Dakar Ouest");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Dakar");
        assertThat(s.values()).doesNotContainKey("departureCity");
        assertThat(s.unresolved()).isEmpty();
    }

    // ---------- C2 : noms de villes en plusieurs mots ----------
    //
    // Le tokenizer sépare déjà « Saint-Louis » en deux tokens simples ("saint",
    // "louis") avant que CityLexicon ne les voie. Regarder chaque mot séparément
    // (l'ancien comportement) trouve pour chacun un match PROPRE — sans ambiguïté
    // locale — mais faux : deux villes différentes de celle tapée, en silence.
    // La correction essaie désormais la paire de mots jointe AVANT le mot seul :
    // les trois tests suivants utilisent les scores trigrammes réels observés sur
    // `cities` pour la requête à deux mots, et vérifient qu'elle l'emporte.

    @Test
    void saintLouis_pairLookupResolvesRealCompoundCityInsteadOfSplittingIt() {
        // Sans l'essai à deux mots : « saint » seul remonterait "Saint-Lô" et
        // « louis » seul "St. Louis" — deux villes fausses. La requête jointe par
        // trait d'union "saint-louis" (la graphie réelle) retrouve les deux vraies
        // lignes "Saint-Louis" (Sénégal, Réunion), départagées par population (C1)
        // sans poser de question.
        when(repository.findSimilar(eq("saint-louis"), anyDouble(), anyInt())).thenReturn(List.of(
            new SearchCityRepository.CityMatch("Saint-Louis", 1.0, 254_171L, "SN"),
            new SearchCityRepository.CityMatch("Saint-Louis", 1.0, 53_935L, "RE")));

        ParseState s = state("à Saint-Louis");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Saint-Louis");
        assertThat(s.unresolved()).isEmpty();
        // Le repli mot à mot n'a jamais dû être tenté : la paire a suffi.
        verify(repository, never()).findSimilar(eq("saint"), anyDouble(), anyInt());
        verify(repository, never()).findSimilar(eq("louis"), anyDouble(), anyInt());
    }

    @Test
    void pointeNoire_pairLookupResolvesDirectlyInsteadOfTwoSeparateAmbiguities() {
        // Avant la correction, « pointe » et « noire » remontaient chacun une
        // ambiguïté locale (contre "Ocean Pointe", "Roches Noire") sans jamais
        // reconstituer "Pointe-Noire". La requête jointe par trait d'union
        // "pointe-noire" la retrouve directement, homonyme Congo/Guadeloupe
        // départagé par population comme pour Saint-Louis.
        when(repository.findSimilar(eq("pointe-noire"), anyDouble(), anyInt())).thenReturn(List.of(
            new SearchCityRepository.CityMatch("Pointe-Noire", 1.0, 1_032_000L, "CG"),
            new SearchCityRepository.CityMatch("Pointe-Noire", 1.0, 7_749L, "GP")));

        ParseState s = state("Pointe-Noire");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Pointe-Noire");
        assertThat(s.unresolved()).isEmpty();
    }

    @Test
    void boboDioulasso_pairLookupResolvesRealCompoundCityInsteadOfSplittingIt() {
        // Sans l'essai à deux mots : « bobo » seul remonterait "Bo" (Sierra Leone,
        // faux) et « dioulasso » seul "Bobo-Dioulasso" (correct par coïncidence) —
        // un couple départ/arrivée fabriqué à partir d'une seule ville tapée. La
        // requête jointe par trait d'union "bobo-dioulasso" retrouve la vraie ville
        // en un coup.
        when(repository.findSimilar(eq("bobo-dioulasso"), anyDouble(), anyInt())).thenReturn(List.of(
            new SearchCityRepository.CityMatch("Bobo-Dioulasso", 0.95, 904_920L, "BF")));

        ParseState s = state("Bobo-Dioulasso");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bobo-Dioulasso");
        assertThat(s.values()).doesNotContainKey("departureCity");
        assertThat(s.unresolved()).isEmpty();
        verify(repository, never()).findSimilar(eq("bobo"), anyDouble(), anyInt());
        verify(repository, never()).findSimilar(eq("dioulasso"), anyDouble(), anyInt());
    }

    @Test
    void unrelatedAdjacentWords_pairAttemptFindsNothingAndFallsBackCleanly() {
        // Contre-épreuve : deux mots inconnus adjacents ne doivent pas faire planter
        // ni fausser le repli mot à mot quand la paire ne matche rien (défaut lenient
        // du setUp). Un seul des deux mots est une vraie ville, précédée de « à » —
        // condition d'émission d'un CITY_UNKNOWN pour l'autre.
        when(repository.findSimilar(eq("bamako"), anyDouble(), anyInt()))
            .thenReturn(List.of(new SearchCityRepository.CityMatch("Bamako", 1.0, 2_700_000L, "ML")));

        ParseState s = state("à Zibulu Bamako");
        lexicon.apply(s);

        assertThat(s.values()).containsEntry("arrivalCity", "Bamako");
    }
}
