package com.yadony.api.assistant.llm;

import com.yadony.api.assistant.provider.ProviderCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmRouterTest {

    private final OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
    private final ProviderCatalog catalog = mock(ProviderCatalog.class);
    private final ProviderHealthRegistry health = new ProviderHealthRegistry(Clock.systemUTC());
    private final LlmRouter router = new LlmRouter(catalog, client, health);
    private final Function<String, String> identity = s -> s;

    private final LlmProviderConfig a = cfg("a");
    private final LlmProviderConfig b = cfg("b");
    private final LlmProviderConfig c = cfg("c");
    private final LlmProviderConfig d = cfg("d");

    private static LlmProviderConfig cfg(String slug) {
        return new LlmProviderConfig(UUID.randomUUID(), slug, "https://" + slug + ".com", "m", "k", true, null);
    }

    @BeforeEach
    void setUp() {
        when(catalog.activeProviders()).thenReturn(List.of(a, b, c, d));
    }

    @Test
    void premierFournisseurRepond() {
        when(client.complete(eq(a), anyString(), anyString())).thenReturn("ok");
        assertThat(router.complete("s", "u", identity)).contains(new LlmResult<>("a", "ok"));
        verify(client, never()).complete(eq(b), anyString(), anyString());
    }

    @Test
    void basculeSur429Puis500() {
        when(client.complete(eq(a), anyString(), anyString())).thenThrow(new LlmCallException(429, "quota"));
        when(client.complete(eq(b), anyString(), anyString())).thenThrow(new LlmCallException(503, "panne"));
        when(client.complete(eq(c), anyString(), anyString())).thenReturn("ok");
        assertThat(router.complete("s", "u", identity)).contains(new LlmResult<>("c", "ok"));
    }

    @Test
    void basculeSurAdresseBloquee() {
        when(client.complete(eq(a), anyString(), anyString()))
                .thenThrow(new LlmCallException(LlmCallException.BLOCKED_ADDRESS, "adresse non publique"));
        when(client.complete(eq(b), anyString(), anyString())).thenReturn("ok");
        assertThat(router.complete("s", "u", identity)).contains(new LlmResult<>("b", "ok"));
        assertThat(health.isAvailable(a)).isFalse();
    }

    @Test
    void basculeSurContenuInvalide() {
        when(client.complete(eq(a), anyString(), anyString())).thenReturn("bavard");
        when(client.complete(eq(b), anyString(), anyString())).thenReturn("{}");
        Function<String, String> parser = s -> {
            if (!s.startsWith("{")) throw new LlmCallException(LlmCallException.INVALID_OUTPUT, "pas du JSON");
            return s;
        };
        assertThat(router.complete("s", "u", parser)).contains(new LlmResult<>("b", "{}"));
    }

    @Test
    void troisEssaisAuPlus() {
        when(client.complete(any(), anyString(), anyString())).thenThrow(new LlmCallException(500, "panne"));
        assertThat(router.complete("s", "u", identity)).isEmpty();
        verify(client, never()).complete(eq(d), anyString(), anyString());
    }

    @Test
    void fournisseurMisDeCoteEstSaute() {
        health.recordFailure(a.id(), 429, "quota");
        when(client.complete(eq(b), anyString(), anyString())).thenReturn("ok");
        assertThat(router.complete("s", "u", identity)).contains(new LlmResult<>("b", "ok"));
        verify(client, never()).complete(eq(a), anyString(), anyString());
    }

    @Test
    void aucunFournisseurActif() {
        when(catalog.activeProviders()).thenReturn(List.of());
        assertThat(router.complete("s", "u", identity)).isEqualTo(Optional.empty());
    }

    @Test
    void chaqueEssaiCompte() {
        when(client.complete(eq(a), anyString(), anyString())).thenThrow(new LlmCallException(500, "panne"));
        when(client.complete(eq(b), anyString(), anyString())).thenReturn("ok");
        router.complete("s", "u", identity);
        assertThat(health.stats(a.id()).requestsToday()).isEqualTo(1);
        assertThat(health.stats(b.id()).successesToday()).isEqualTo(1);
    }
}
