package com.yadony.api.search;

import com.yadony.api.config.ContentCatalog;
import com.yadony.api.config.dto.ContentCategoryResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ContentTypeParserTest {

    private static ParseState state(String text) {
        return new ParseState(SearchTokenizer.tokenize(text), SearchMode.TRIPS,
                LocalDate.of(2026, 8, 19));
    }

    @Test
    void clothesResolveToTheCanonicalLabelNotTheCode() {
        // Piège : c'est le label qui est persisté en base, jamais le code.
        ParseState s = state("vêtements");

        ContentTypeParser.apply(s);

        assertThat(s.values()).containsEntry("contentType", "Vêtements & tissus");
    }

    @Test
    void everyProducedValueExistsInTheCanonicalCatalog() {
        ParseState s = state("médicaments");

        ContentTypeParser.apply(s);

        Object produced = s.values().get("contentType");
        assertThat(ContentCatalog.CATEGORIES)
            .extracting(ContentCategoryResponse::label)
            .contains((String) produced);
    }

    @Test
    void foodResolvesToTheDryFoodCategory() {
        ParseState s = state("riz");

        ContentTypeParser.apply(s);

        assertThat(s.values()).containsEntry("contentType", "Alimentation sèche");
    }

    @Test
    void phoneResolvesToTheElectronicsCategory() {
        ParseState s = state("téléphone");

        ContentTypeParser.apply(s);

        assertThat(s.values()).containsEntry("contentType", "Téléphone & électronique");
    }

    @Test
    void recognizedCategoryIsConsumed() {
        ParseState s = state("vêtements");

        ContentTypeParser.apply(s);

        assertThat(s.remaining()).isEmpty();
    }

    @Test
    void unknownWordIsLeftForLaterPasses() {
        ParseState s = state("Bamako");

        ContentTypeParser.apply(s);

        assertThat(s.values()).isEmpty();
        assertThat(s.remaining()).hasSize(1);
    }

    @Test
    void onlyFirstCategoryIsRecognized() {
        // Test que seule la première catégorie est reconnue quand plusieurs sont présentes
        ParseState s = state("vêtements et riz");

        ContentTypeParser.apply(s);

        // Seule la première ("vêtements") est reconnue et consommée
        assertThat(s.values()).containsEntry("contentType", "Vêtements & tissus");
        // "et" et "riz" restent
        assertThat(s.remaining()).hasSize(2);
    }

    @Test
    void multipleCategoriesVocabularyWords() {
        // Couvre plusieurs branches du vocabulaire
        ParseState s1 = state("cadeau");
        ContentTypeParser.apply(s1);
        assertThat(s1.values()).containsEntry("contentType", "Cadeaux & jouets");

        ParseState s2 = state("chaussures");
        ContentTypeParser.apply(s2);
        assertThat(s2.values()).containsEntry("contentType", "Chaussures");

        ParseState s3 = state("livre");
        ContentTypeParser.apply(s3);
        assertThat(s3.values()).containsEntry("contentType", "Livres");
    }

    @Test
    void accentRemovalWorksCorrectly() {
        // Le tokeniseur retire les accents : "épices" → "epices" → reconnu (ALIMENTATION_SECHE)
        ParseState s = state("épices");

        ContentTypeParser.apply(s);

        assertThat(s.values()).containsEntry("contentType", "Alimentation sèche");
    }
}
