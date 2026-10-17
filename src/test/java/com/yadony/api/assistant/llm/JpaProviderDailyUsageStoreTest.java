package com.yadony.api.assistant.llm;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Compteurs en base (H2, mode PostgreSQL) : mêmes requêtes que la prod. */
@SpringBootTest
@ActiveProfiles("test")
class JpaProviderDailyUsageStoreTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 16);

    @Autowired JpaProviderDailyUsageStore store;

    @Test
    void reservationsConcurrentesSansLimiteDonnentUnTotalExact() throws Exception {
        UUID id = UUID.randomUUID();
        int accordes = ProviderDailyUsageConcurrency.reserveConcurrently(store, id, DAY, ProviderDailyUsageStore.NO_LIMIT, 8, 25);
        assertThat(accordes).isEqualTo(200);
        assertThat(store.read(List.of(id), DAY).get(id).requests()).isEqualTo(200);
    }

    @Test
    void laLimiteTientSousConcurrence() throws Exception {
        UUID id = UUID.randomUUID();
        int accordes = ProviderDailyUsageConcurrency.reserveConcurrently(store, id, DAY, 5, 10, 4);
        assertThat(accordes).isEqualTo(5);
        assertThat(store.read(List.of(id), DAY).get(id).requests()).isEqualTo(5);
    }

    @Test
    void succesConcurrentsDonnentUnTotalExact() throws Exception {
        UUID id = UUID.randomUUID();
        ProviderDailyUsageConcurrency.runConcurrently(8, 10, () -> store.addSuccess(id, DAY));
        ProviderDailyUsageStore.DailyCount c = store.read(List.of(id), DAY).get(id);
        assertThat(c.successes()).isEqualTo(80);
        assertThat(c.requests()).isZero();
    }

    @Test
    void unJourParLigneEtLectureGroupee() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertThat(store.tryReserve(a, DAY, 1)).isTrue();
        assertThat(store.tryReserve(a, DAY, 1)).isFalse();
        assertThat(store.tryReserve(a, DAY.plusDays(1), 1)).isTrue();
        store.tryReserve(b, DAY, 10);
        store.addSuccess(b, DAY);

        var counts = store.read(List.of(a, b, UUID.randomUUID()), DAY);
        assertThat(counts).hasSize(2);
        assertThat(counts.get(a).requests()).isEqualTo(1);
        assertThat(counts.get(b).requests()).isEqualTo(1);
        assertThat(counts.get(b).successes()).isEqualTo(1);
        assertThat(store.read(List.of(), DAY)).isEmpty();
    }

    @Test
    void unNouveauRegistreRelitLesCompteursEnBase() {
        UUID id = UUID.randomUUID();
        Clock clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);
        LlmProviderConfig provider = new LlmProviderConfig(id, "claude", "https://a.com", "m", "k", true, 2);
        ProviderHealthRegistry avant = new ProviderHealthRegistry(store, clock);
        assertThat(avant.tryAcquire(provider)).isTrue();
        avant.recordSuccess(id);

        ProviderHealthRegistry apres = new ProviderHealthRegistry(store, clock);
        assertThat(apres.stats(id).requestsToday()).isEqualTo(1);
        assertThat(apres.stats(id).successesToday()).isEqualTo(1);
        assertThat(apres.tryAcquire(provider)).isTrue();
        assertThat(apres.tryAcquire(provider)).isFalse();

        ProviderHealthRegistry lendemain = new ProviderHealthRegistry(store,
                Clock.fixed(Instant.parse("2026-10-17T00:00:01Z"), ZoneOffset.UTC));
        assertThat(lendemain.stats(id).requestsToday()).isZero();
        assertThat(lendemain.tryAcquire(provider)).isTrue();
    }
}
