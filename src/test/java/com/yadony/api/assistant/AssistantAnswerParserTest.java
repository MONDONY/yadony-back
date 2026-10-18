package com.yadony.api.assistant;

import com.yadony.api.assistant.dto.ParsedAnswer;
import com.yadony.api.assistant.llm.LlmCallException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantAnswerParserTest {

    private final AssistantAnswerParser parser = new AssistantAnswerParser(new com.fasterxml.jackson.databind.ObjectMapper());

    @Test
    void litUneReponseComplete() {
        ParsedAnswer a = parser.parse("{\"answer\":\"Allez dans Moi.\",\"answered\":true,\"routes\":[\"/profile/edit/phone\"]}");
        assertThat(a.answer()).isEqualTo("Allez dans Moi.");
        assertThat(a.answered()).isTrue();
        assertThat(a.routes()).containsExactly("/profile/edit/phone");
    }

    @Test
    void retireLesBalisesDeCode() {
        ParsedAnswer a = parser.parse("```json\n{\"answer\":\"ok\",\"answered\":false}\n```");
        assertThat(a.answer()).isEqualTo("ok");
        assertThat(a.routes()).isEmpty();
    }

    @Test
    void ignoreLesRoutesNonTextuelles() {
        assertThat(parser.parse("{\"answer\":\"ok\",\"answered\":true,\"routes\":[1,\"/a\",null]}").routes())
                .containsExactly("/a");
    }

    @Test
    void litLeDrapeauDuColis() {
        assertThat(parser.parse("{\"answer\":\"ok\",\"answered\":true,\"about_my_parcel\":true}").aboutMyParcel()).isTrue();
        assertThat(parser.parse("{\"answer\":\"ok\",\"answered\":true}").aboutMyParcel()).isFalse();
        assertThat(parser.parse("{\"answer\":\"ok\",\"answered\":true,\"about_my_parcel\":\"true\"}").aboutMyParcel())
                .isFalse();
    }

    @Test
    void refuseLeTexteLibre() {
        assertInvalid("Bien sûr ! Voici comment faire.");
    }

    @Test
    void refuseUnJsonIncomplet() {
        assertInvalid("{\"answered\":true}");
        assertInvalid("{\"answer\":\"  \",\"answered\":true}");
        assertInvalid("{\"answer\":\"ok\"}");
        assertInvalid("[]");
    }

    @Test
    void routesAbsentesOuNonTableauDonnentListeVide() {
        assertThat(parser.parse("{\"answer\":\"ok\",\"answered\":true}").routes()).isEmpty();
        assertThat(parser.parse("{\"answer\":\"ok\",\"answered\":true,\"routes\":\"x\"}").routes()).isEmpty();
    }

    @Test
    void listeDeRoutesEstImmuable() {
        ParsedAnswer a = parser.parse("{\"answer\":\"ok\",\"answered\":true,\"routes\":[\"/a\"]}");
        assertThatThrownBy(() -> a.routes().add("/b")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void answeredEnChaineEstRefuse() {
        assertInvalid("{\"answer\":\"ok\",\"answered\":\"true\"}");
    }

    private void assertInvalid(String content) {
        assertThatThrownBy(() -> parser.parse(content)).isInstanceOfSatisfying(LlmCallException.class,
                e -> assertThat(e.status()).isEqualTo(LlmCallException.INVALID_OUTPUT));
    }

    @Test
    void litLesSuggestions() {
        ParsedAnswer a = parser.parse("{\"answer\":\"ok\",\"answered\":true,\"follow_ups\":[\"Et pour 10 kg ?\",3,null,\"Qui paie ?\"],"
                + "\"discovery\":{\"id\":\"parrainage\",\"question\":\"Comment parrainer un ami ?\"}}");
        assertThat(a.followUps()).containsExactly("Et pour 10 kg ?", "Qui paie ?");
        assertThat(a.discoveryId()).isEqualTo("parrainage");
        assertThat(a.discoveryQuestion()).isEqualTo("Comment parrainer un ami ?");
    }

    @Test
    void suggestionsAbsentesOuMalFormeesNeFontPasEchouer() {
        ParsedAnswer absent = parser.parse("{\"answer\":\"ok\",\"answered\":true}");
        assertThat(absent.followUps()).isEmpty();
        assertThat(absent.discoveryId()).isNull();
        ParsedAnswer bad = parser.parse("{\"answer\":\"ok\",\"answered\":true,\"follow_ups\":\"Et après ?\","
                + "\"discovery\":\"parrainage\"}");
        assertThat(bad.followUps()).isEmpty();
        assertThat(bad.discoveryId()).isNull();
        ParsedAnswer partial = parser.parse("{\"answer\":\"ok\",\"answered\":true,\"discovery\":{\"id\":7,\"question\":\"Q ?\"}}");
        assertThat(partial.discoveryId()).isNull();
        assertThat(partial.discoveryQuestion()).isEqualTo("Q ?");
        assertThat(parser.parse("{\"answer\":\"ok\",\"answered\":true,\"discovery\":null}").discoveryId()).isNull();
    }
}
