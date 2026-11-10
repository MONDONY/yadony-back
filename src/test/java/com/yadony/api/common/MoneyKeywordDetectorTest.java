package com.yadony.api.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MoneyKeywordDetector : mots qui évoquent l'envoi d'argent dans une description")
class MoneyKeywordDetectorTest {

    @ParameterizedTest(name = "« {0} » → {1}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "Bague en ARGENT                     | argent",
            "des Espèces pour maman              | especes",
            "une enveloppe d'argent              | argent",
            "2 liasses                           | liasses",
            "cash                                | cash",
            "Chèque de 200 €                     | cheque",
            "carte prépayée                      | carte prepayee",
            "Carte   Bancaire                    | carte bancaire",
            "billets de banque                   | billets de banque",
            "des devises                         | devises",
            "some money for my brother           | money",
            "a prepaid card                      | prepaid card",
            "BANKNOTES                           | banknotes",
            "vêtements, cash, chaussures         | cash",
            "(cash)                              | cash",
    })
    void reconnaitLesTermes(String text, String expected) {
        assertThat(MoneyKeywordDetector.firstMatch(text)).contains(expected);
    }

    @ParameterizedTest(name = "« {0} » ne déclenche rien")
    @ValueSource(strings = {
            "Maillot de l'Argentine",
            "Argenterie de famille",
            "Bracelet argenté",
            "billets d'avion imprimés",
            "enveloppe de documents",
            "une espèce de sac",
            "devise de l'école brodée",
            "vêtements et chaussures",
            "cashmere scarf",
    })
    void ignoreLesFauxAmis(String text) {
        assertThat(MoneyKeywordDetector.firstMatch(text)).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void texteVideOuAbsent(String text) {
        assertThat(MoneyKeywordDetector.firstMatch(text)).isEmpty();
    }

    @ParameterizedTest(name = "normalise « {0} »")
    @CsvSource(delimiter = '|', quoteCharacter = '"', value = {
            "Espèces   | especes",
            "d'argent  | d argent",
            "PRÉPAYÉE! | \"prepayee \"",
    })
    void normaliseAccentsCasseEtPonctuation(String raw, String expected) {
        assertThat(MoneyKeywordDetector.normalize(raw)).isEqualTo(expected);
    }
}
