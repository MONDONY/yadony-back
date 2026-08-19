package com.yadony.api.search;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class FlagParserTest {

    private static ParseState state(String text) {
        return new ParseState(SearchTokenizer.tokenize(text), SearchMode.TRIPS,
                LocalDate.of(2026, 8, 19));
    }

    @Test
    void urgentSetsTheUrgentFlag() {
        ParseState s = state("urgent");

        FlagParser.apply(s);

        assertThat(s.values()).containsEntry("urgent", Boolean.TRUE);
    }

    @Test
    void verifiedSetsTheKycFlag() {
        ParseState s = state("vérifié");

        FlagParser.apply(s);

        assertThat(s.values()).containsEntry("kycVerifiedOnly", Boolean.TRUE);
    }

    @Test
    void identityPapersDoNotPhantomSetTheKycFlag() {
        // I3 : « identite » posait kycVerifiedOnly même sur « papiers d'identité »,
        // qui décrit le CONTENU du colis, pas une exigence sur le voyageur.
        ParseState s = state("papiers d'identité pour mon fils");

        FlagParser.apply(s);

        assertThat(s.values()).doesNotContainKey("kycVerifiedOnly");
    }

    @Test
    void kiloProSetsTheProFlag() {
        ParseState s = state("kilo pro");

        FlagParser.apply(s);

        assertThat(s.values()).containsEntry("kiloProOnly", Boolean.TRUE);
    }

    @Test
    void wellRatedAlignsOnTheExistingChipThreshold() {
        // La puce de l'app s'intitule « Note ≥ 4.5 » : le parseur produit la même valeur.
        ParseState s = state("bien noté");

        FlagParser.apply(s);

        assertThat(s.values()).containsEntry("minRating", new BigDecimal("4.5"));
    }

    @Test
    void planeSetsTheTransportMode() {
        ParseState s = state("en avion");

        FlagParser.apply(s);

        assertThat(s.values()).containsEntry("transportMode", "PLANE");
    }

    @Test
    void boatSetsTheTransportMode() {
        ParseState s = state("par bateau");

        FlagParser.apply(s);

        assertThat(s.values()).containsEntry("transportMode", "BOAT");
    }

    @Test
    void recognizedFlagsAreConsumed() {
        ParseState s = state("urgent");

        FlagParser.apply(s);

        assertThat(s.remaining()).isEmpty();
    }

    @Test
    void unrelatedWordsAreLeftForLaterPasses() {
        ParseState s = state("Bamako");

        FlagParser.apply(s);

        assertThat(s.values()).isEmpty();
        assertThat(s.remaining()).hasSize(1);
    }
}
