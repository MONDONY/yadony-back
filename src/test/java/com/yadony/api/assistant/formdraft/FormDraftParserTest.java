package com.yadony.api.assistant.formdraft;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.assistant.llm.LlmCallException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FormDraftParserTest {

    private final FormDraftParser parser = new FormDraftParser(new ObjectMapper());

    @Test
    void litUnObjetComplet() {
        ModelOutput out = parser.parse("""
                {"draft":{"departureCity":"Paris","availableKg":23},"needsConfirmation":true,
                 "question":"Le 15 novembre ?","quickReplies":["Oui","Non"]}""");
        assertThat(out.draft()).containsEntry("departureCity", "Paris").containsEntry("availableKg", 23);
        assertThat(out.needsConfirmation()).isTrue();
        assertThat(out.question()).isEqualTo("Le 15 novembre ?");
        assertThat(out.quickReplies()).containsExactly("Oui", "Non");
    }

    @Test
    void retireLesClotures() {
        ModelOutput out = parser.parse("```json\n{\"draft\":{}}\n```");
        assertThat(out.draft()).isEmpty();
        assertThat(out.needsConfirmation()).isFalse();
        assertThat(out.question()).isNull();
        assertThat(out.quickReplies()).isEmpty();
    }

    @Test
    void questionVideDevientNulleEtReponsesNonTexteIgnorees() {
        ModelOutput out = parser.parse("{\"draft\":{},\"question\":\"  \",\"quickReplies\":[\"Oui\",3,null]}");
        assertThat(out.question()).isNull();
        assertThat(out.quickReplies()).containsExactly("Oui");
    }

    @Test
    void jsonTronqueLeveInvalidOutput() {
        assertThatThrownBy(() -> parser.parse("{\"draft\":{\"departureCity\":"))
                .isInstanceOfSatisfying(LlmCallException.class,
                        e -> assertThat(e.status()).isEqualTo(LlmCallException.INVALID_OUTPUT));
    }

    @Test
    void sansDraftObjetLeveInvalidOutput() {
        assertThatThrownBy(() -> parser.parse("{\"draft\":\"Paris\"}")).isInstanceOf(LlmCallException.class);
        assertThatThrownBy(() -> parser.parse("[1,2]")).isInstanceOf(LlmCallException.class);
    }
}
