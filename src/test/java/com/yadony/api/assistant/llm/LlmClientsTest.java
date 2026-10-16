package com.yadony.api.assistant.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LlmClientsTest {

    private final OpenAiCompatibleClient openAi = mock(OpenAiCompatibleClient.class);
    private final AnthropicMessagesClient anthropic = mock(AnthropicMessagesClient.class);
    private final LlmClients clients = new LlmClients(openAi, anthropic);

    private static LlmProviderConfig provider(String baseUrl, String model) {
        return new LlmProviderConfig(UUID.randomUUID(), "p", baseUrl, model, "key", false, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.anthropic.com", "https://api.anthropic.com/v1", "HTTPS://API.ANTHROPIC.COM",
            "https://api.anthropic.com:443", "https://api.anthropic.com/"})
    void reconnaitAnthropic(String url) {
        assertThat(LlmClients.isAnthropic(url)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"http://api.anthropic.com", "https://api.anthropic.com.evil.com", "https://evil.com/api.anthropic.com",
            "https://xapi.anthropic.com", "https://anthropic.com", "https://api.anthropic.com:8443",
            "https://user@api.anthropic.com", "https://api.groq.com/openai/v1", "pas une url", "https://api.anthropic .com"})
    void neReconnaitPasLesAutresAdresses(String url) {
        assertThat(LlmClients.isAnthropic(url)).isFalse();
    }

    @Test
    void anthropicPasseParLeClientNatifAvecNImporteQuelModele() {
        LlmProviderConfig p = provider("https://api.anthropic.com", "claude-modele-futur-9");
        when(anthropic.complete(eq(p), eq("s"), eq("u"), any(Duration.class))).thenReturn("ok");
        assertThat(clients.complete(p, "s", "u")).isEqualTo("ok");
        verify(anthropic).complete(p, "s", "u", LlmClients.MAX_CALL_TIMEOUT);
        verifyNoInteractions(openAi);
    }

    @Test
    void lesAutresFournisseursRestentSurLeClientOpenAi() {
        LlmProviderConfig p = provider("https://api.groq.com/openai/v1", "llama-3.3-70b-versatile");
        when(openAi.complete(eq(p), eq("s"), eq("u"), any(Duration.class))).thenReturn("ok");
        assertThat(clients.complete(p, "s", "u", Duration.ofSeconds(7))).isEqualTo("ok");
        verify(openAi).complete(p, "s", "u", Duration.ofSeconds(7));
        verifyNoInteractions(anthropic);
    }
}
