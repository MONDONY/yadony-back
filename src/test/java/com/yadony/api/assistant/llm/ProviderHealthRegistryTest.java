package com.yadony.api.assistant.llm;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    void reinitialiserLeveLaMiseDeCoteEtLaDerniereErreurMaisGardeLesCompteurs() {
        registry.tryAcquire(provider(null));
        registry.recordSuccess(id);
        registry.recordFailure(id, 401, "clé");
        assertThat(registry.isAvailable(provider(null))).isFalse();

        registry.reset(id);

        assertThat(registry.isAvailable(provider(null))).isTrue();
        ProviderHealthRegistry.ProviderStats stats = registry.stats(id);
        assertThat(stats.cooldownUntil()).isNull();
        assertThat(stats.lastError()).isNull();
        assertThat(stats.requestsToday()).isEqualTo(1);
        assertThat(stats.successesToday()).isEqualTo(1);
    }

    @Test
    void reinitialiserUnFournisseurInconnuNeLeveRien() {
        registry.reset(UUID.randomUUID());
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

    @Test
    void tryAcquireRefuseLaLimiteDuJourSansCompterAuDela() {
        assertThat(registry.tryAcquire(provider(1))).isTrue();
        assertThat(registry.tryAcquire(provider(1))).isFalse();
        assertThat(registry.stats(id).requestsToday()).isEqualTo(1);
    }

    @Test
    void tryAcquireRefuseUnFournisseurMisDeCote() {
        registry.recordFailure(id, 429, "quota");
        assertThat(registry.tryAcquire(provider(null))).isFalse();
        assertThat(registry.stats(id).requestsToday()).isZero();
    }

    @Test
    void tryAcquireNeDepasseJamaisLaLimiteEnConcurrence() throws Exception {
        CountDownLatch depart = new CountDownLatch(1);
        List<Future<Boolean>> resultats = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                resultats.add(pool.submit(() -> {
                    depart.await();
                    return registry.tryAcquire(provider(5));
                }));
            }
            depart.countDown();
            long accordes = 0;
            for (Future<Boolean> resultat : resultats) {
                if (resultat.get()) accordes++;
            }
            assertThat(accordes).isEqualTo(5);
        }
        assertThat(registry.stats(id).requestsToday()).isEqualTo(5);
    }
}
