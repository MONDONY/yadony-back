package com.yadony.api.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchTokenizerTest {

    @Test
    void tokenize_splitsOnWhitespaceAndPunctuation() {
        List<Token> tokens = SearchTokenizer.tokenize("20 kilos à Bamako");

        assertThat(tokens).extracting(Token::raw)
            .containsExactly("20", "kilos", "à", "Bamako");
    }

    @Test
    void tokenize_normalizesToLowercaseWithoutAccents() {
        List<Token> tokens = SearchTokenizer.tokenize("Kédougou");

        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0).raw()).isEqualTo("Kédougou");
        assertThat(tokens.get(0).normalized()).isEqualTo("kedougou");
    }

    @Test
    void tokenize_keepsSpansPointingAtTheOriginalText() {
        List<Token> tokens = SearchTokenizer.tokenize("20 kilos à Bamako");

        Token bamako = tokens.get(3);
        assertThat("20 kilos à Bamako".substring(bamako.start(), bamako.end()))
            .isEqualTo("Bamako");
    }

    @Test
    void tokenize_dropsIntentNoise() {
        List<Token> tokens = SearchTokenizer.tokenize("je veux envoyer 20 kilos");

        assertThat(tokens).extracting(Token::normalized)
            .containsExactly("20", "kilos");
    }

    @Test
    void tokenize_keepsEuroSignAsItsOwnToken() {
        List<Token> tokens = SearchTokenizer.tokenize("8€/kg");

        assertThat(tokens).extracting(Token::normalized)
            .containsExactly("8", "€", "kg");
    }

    @Test
    void tokenize_splitsDigitsFromLettersWhenGlued() {
        // « 15kg » doit donner un nombre et une unité, sinon la passe quantités
        // ne verra jamais l'unité qui suit le nombre.
        List<Token> tokens = SearchTokenizer.tokenize("15kg");

        assertThat(tokens).extracting(Token::normalized)
            .containsExactly("15", "kg");
    }

    @Test
    void tokenize_onBlankText_returnsEmptyList() {
        assertThat(SearchTokenizer.tokenize("   ")).isEmpty();
    }

    @Test
    void tokenize_keepsValiseAndCartonButDropsColisAndPaquetAndBagage() {
        // « valise » (23 kg) et « carton » (15 kg) portent un poids conventionnel exploité
        // par la passe quantités (Task 2) : ils ne doivent jamais rejoindre NOISE, contrairement
        // à « colis », « paquet » et « bagage » qui ne portent aucune valeur de filtre.
        List<Token> tokens = SearchTokenizer.tokenize(
                "je veux envoyer une valise et un carton, pas un colis ni un paquet ni un bagage");

        assertThat(tokens).extracting(Token::normalized)
                .contains("valise", "carton")
                .doesNotContain("colis", "paquet", "bagage");
    }
}
