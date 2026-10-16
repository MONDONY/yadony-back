package com.yadony.api.assistant.llm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderHealthRegistryTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-16T10:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final ProviderHealthRegistry registry = new ProviderHealthRegistry(clock);
    private final UUID id = UUID.randomUUID();

    private LlmProviderConfig provider(Integer limit) {
        return new LlmProviderConfig(id, "groq", "https://a.com", "m", "k", true, limit);
    }

    @Test
    void miseDeCoteSoixanteSecondesApres429() {
        registry.recordFailure(id, 429, "quota");
        assertThat(registry.isAvailable(provider(null))).isFalse();
        now.set(now.get().plusSeconds(61));
        assertThat(registry.isAvailable(provider(null))).isTrue();
    }

    @Test
    void miseDeCoteDixMinutesApres401() {
        registry.recordFailure(id, 401, "clé");
        now.set(now.get().plusSeconds(9 * 60));
        assertThat(registry.isAvailable(provider(null))).isFalse();
        now.set(now.get().plusSeconds(2 * 60));
        assertThat(registry.isAvailable(provider(null))).isTrue();
    }

    @Test
    void miseDeCoteDixMinutesApresAdresseBloquee() {
        registry.recordFailure(id, LlmCallException.BLOCKED_ADDRESS, "adresse non publique");
        now.set(now.get().plusSeconds(9 * 60));
        assertThat(registry.isAvailable(provider(null))).isFalse();
        now.set(now.get().plusSeconds(2 * 60));
        assertThat(registry.isAvailable(provider(null))).isTrue();
    }

    @Test
    void erreur500NeMetPasDeCote() {
        registry.recordFailure(id, 500, "panne");
        assertThat(registry.isAvailable(provider(null))).isTrue();
        assertThat(registry.stats(id).lastStatus()).isEqualTo(500);
    }

    @Test
    void limiteDuJourPuisRemiseAZeroLeLendemain() {
        registry.recordAttempt(id);
        registry.recordAttempt(id);
        assertThat(registry.isAvailable(provider(2))).isFalse();
        now.set(Instant.parse("2026-10-17T00:00:01Z"));
        assertThat(registry.isAvailable(provider(2))).isTrue();
        assertThat(registry.stats(id).requestsToday()).isZero();
    }

    @Test
    void succesEffaceLaDerniereErreur() {
        registry.recordAttempt(id);
        registry.recordFailure(id, 500, "panne");
        registry.recordAttempt(id);
        registry.recordSuccess(id);
        ProviderHealthRegistry.ProviderStats s = registry.stats(id);
        assertThat(s.requestsToday()).isEqualTo(2);
        assertThat(s.successesToday()).isEqualTo(1);
        assertThat(s.lastStatus()).isEqualTo(200);
        assertThat(s.lastError()).isNull();
    }

    @Test
    void fournisseurInconnuADesChiffresVides() {
        assertThat(registry.stats(UUID.randomUUID()).requestsToday()).isZero();
    }
}
