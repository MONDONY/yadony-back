package com.yadony.api.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPropertiesTest {

    @Test
    void seuilParDefautSiAbsent() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("yadony.assistant.enabled", "true")));
        AssistantProperties properties = binder.bind("yadony.assistant", AssistantProperties.class).get();
        assertThat(properties.minScore()).isEqualTo(6.5);
        assertThat(properties.enabled()).isTrue();
    }

    @Test
    void seuilConfigurable() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("yadony.assistant.min-score", "3.25")));
        assertThat(binder.bind("yadony.assistant", AssistantProperties.class).get().minScore()).isEqualTo(3.25);
    }
}
