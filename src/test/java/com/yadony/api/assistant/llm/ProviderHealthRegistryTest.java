package com.yadony.api.assistant.llm;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProviderHealthRegistryTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-16T10:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final InMemoryProviderDailyUsageStore store = new InMemoryProviderDailyUsageStore();
    private final ProviderHealthRegistry registry = new ProviderHealthRegistry(store, clock);
    private final UUID id = UUID.randomUUID();

    private LlmProviderConfig provider(Integer limit) {
        return new LlmProviderConfig(id, "groq", "https://a.com", "m", "k", true, limit);
    }

    @Test
    void miseDeCoteSoixanteSecondesApres429() {
        registry.recordFailure(id, 429, "quota");
        assertThat(registry.tryAcquire(provider(null))).isFalse();
        now.set(now.get().plusSeconds(61));
        assertThat(registry.tryAcquire(provider(null))).isTrue();
    }

    @Test
    void reinitialiserLeveLaMiseDeCoteEtLaDerniereErreurMaisGardeLesCompteurs() {
        registry.tryAcquire(provider(null));
        registry.recordSuccess(id);
        registry.recordFailure(id, 401, "clé");
        assertThat(registry.stats(id).cooldownUntil()).isNotNull();

        registry.reset(id);

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
        assertThat(registry.tryAcquire(provider(null))).isFalse();
        now.set(now.get().plusSeconds(2 * 60));
        assertThat(registry.tryAcquire(provider(null))).isTrue();
    }

    @Test
    void miseDeCoteDixMinutesApresAdresseBloquee() {
        registry.recordFailure(id, LlmCallException.BLOCKED_ADDRESS, "adresse non publique");
        now.set(now.get().plusSeconds(9 * 60));
        assertThat(registry.tryAcquire(provider(null))).isFalse();
        now.set(now.get().plusSeconds(2 * 60));
        assertThat(registry.tryAcquire(provider(null))).isTrue();
    }

    @Test
    void miseDeCoteDixMinutesApres403() {
        registry.recordFailure(id, 403, "interdit");
        now.set(now.get().plusSeconds(9 * 60));
        assertThat(registry.tryAcquire(provider(null))).isFalse();
        now.set(now.get().plusSeconds(2 * 60));
        assertThat(registry.tryAcquire(provider(null))).isTrue();
    }

    @Test
    void messageNulOuTropLongEstBorne() {
        registry.recordFailure(id, 500, null);
        assertThat(registry.stats(id).lastError()).isNull();
        registry.recordFailure(id, 500, "x".repeat(500));
        assertThat(registry.stats(id).lastError()).hasSize(200);
        registry.recordFailure(id, 500, "y".repeat(200));
        assertThat(registry.stats(id).lastError()).hasSize(200);
    }

    @Test
    void erreur500NeMetPasDeCote() {
        registry.recordFailure(id, 500, "panne");
        assertThat(registry.tryAcquire(provider(null))).isTrue();
        assertThat(registry.stats(id).lastStatus()).isEqualTo(500);
    }

    @Test
    void limiteDuJourPuisRemiseAZeroLeLendemain() {
        assertThat(registry.tryAcquire(provider(2))).isTrue();
        assertThat(registry.tryAcquire(provider(2))).isTrue();
        assertThat(registry.tryAcquire(provider(2))).isFalse();
        now.set(Instant.parse("2026-10-17T00:00:01Z"));
        assertThat(registry.stats(id).requestsToday()).isZero();
        assertThat(registry.tryAcquire(provider(2))).isTrue();
        assertThat(registry.stats(id).requestsToday()).isEqualTo(1);
    }

    @Test
    void succesEffaceLaDerniereErreur() {
        registry.tryAcquire(provider(null));
        registry.recordFailure(id, 500, "panne");
        registry.tryAcquire(provider(null));
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

    @Test
    void lesCompteursSurviventAUnRedemarrageMaisPasLaMiseDeCote() {
        assertThat(registry.tryAcquire(provider(3))).isTrue();
        registry.recordSuccess(id);
        assertThat(registry.tryAcquire(provider(3))).isTrue();
        registry.recordFailure(id, 429, "quota");

        // Redémarrage : nouvelle instance du registre, même base.
        ProviderHealthRegistry apres = new ProviderHealthRegistry(store, clock);
        ProviderHealthRegistry.ProviderStats s = apres.stats(id);
        assertThat(s.requestsToday()).isEqualTo(2);
        assertThat(s.successesToday()).isEqualTo(1);
        assertThat(s.cooldownUntil()).isNull();
        assertThat(s.lastStatus()).isNull();
        assertThat(apres.tryAcquire(provider(3))).isTrue();
        assertThat(apres.tryAcquire(provider(3))).isFalse();
    }

    @Test
    void statsDePlusieursFournisseursEnUneLecture() {
        UUID autre = UUID.randomUUID();
        registry.tryAcquire(provider(null));
        registry.recordSuccess(id);
        store.reads = 0;

        Map<UUID, ProviderHealthRegistry.ProviderStats> stats = registry.stats(List.of(id, autre));

        assertThat(store.reads).isEqualTo(1);
        assertThat(stats.get(id).requestsToday()).isEqualTo(1);
        assertThat(stats.get(id).successesToday()).isEqualTo(1);
        assertThat(stats.get(autre).requestsToday()).isZero();
    }

    @Test
    void baseInjoignableLaissePasserDansLaLimiteDuJourEnMemoire() {
        ProviderDailyUsageStore enPanne = mock(ProviderDailyUsageStore.class);
        when(enPanne.tryReserve(any(), any(), anyInt())).thenThrow(new DataAccessResourceFailureException("down"));
        doThrow(new CannotCreateTransactionException("down")).when(enPanne).addSuccess(any(), any());
        when(enPanne.read(anyCollection(), any())).thenReturn(Map.of());
        ProviderHealthRegistry r = new ProviderHealthRegistry(enPanne, clock);

        assertThat(r.tryAcquire(provider(2))).isTrue();
        r.recordSuccess(id);
        assertThat(r.tryAcquire(provider(2))).isTrue();
        assertThat(r.tryAcquire(provider(2))).isFalse();
        assertThat(r.stats(id).requestsToday()).isEqualTo(2);
        assertThat(r.stats(id).lastStatus()).isEqualTo(200);

        now.set(Instant.parse("2026-10-17T00:00:01Z"));
        assertThat(r.stats(id).requestsToday()).isZero();
        assertThat(r.tryAcquire(provider(2))).isTrue();
    }

    @Test
    void baseInjoignableSansLimiteLaissePasser() {
        ProviderDailyUsageStore enPanne = mock(ProviderDailyUsageStore.class);
        when(enPanne.tryReserve(any(), any(), anyInt())).thenThrow(new DataAccessResourceFailureException("down"));
        ProviderHealthRegistry r = new ProviderHealthRegistry(enPanne, clock);
        for (int i = 0; i < 5; i++) assertThat(r.tryAcquire(provider(null))).isTrue();
    }

    @Test
    void laReservationUtiliseLeJourUtc() {
        now.set(Instant.parse("2026-10-16T23:59:59Z"));
        registry.tryAcquire(provider(null));
        now.set(Instant.parse("2026-10-17T00:00:00Z"));
        registry.tryAcquire(provider(null));
        assertThat(store.read(List.of(id), LocalDate.of(2026, 10, 16)).get(id).requests()).isEqualTo(1);
        assertThat(store.read(List.of(id), LocalDate.of(2026, 10, 17)).get(id).requests()).isEqualTo(1);
    }
}
