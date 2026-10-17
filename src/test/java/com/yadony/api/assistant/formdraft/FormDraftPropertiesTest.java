package com.yadony.api.assistant.formdraft;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FormDraftPropertiesTest {

    @Test
    void valeursParDefaut() {
        FormDraftProperties p = new Binder(new MapConfigurationPropertySource(
                Map.of("yadony.assistant.form-draft.enabled", "true")))
                .bind("yadony.assistant.form-draft", FormDraftProperties.class).get();
        assertThat(p.enabled()).isTrue();
        assertThat(p.dailyQuota()).isEqualTo(30);
        assertThat(p.maxTurns()).isEqualTo(8);
    }
}
