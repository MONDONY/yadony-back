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
}
