package com.yadony.api.admin;

import com.yadony.api.city.GeoNamesDataLoader;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** La prise en charge d'une alerte est atomique : un seul admin la gagne, même simultanés (PostgreSQL). */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Prise en charge d'alerte : atomicité (PostgreSQL)")
class AdminAlertClaimConcurrencyIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    @Autowired AdminAlertRepository repo;
    @Autowired TransactionTemplate tx;
    @MockitoBean GeoNamesDataLoader geoNamesDataLoader;

    private int claim(UUID id, UUID admin, OffsetDateTime now) {
        Integer n = tx.execute(s -> repo.claimIfFree(id, admin, now));
        return n == null ? -1 : n;
    }

    private int release(UUID id, UUID claimer) {
        Integer n = tx.execute(s -> repo.releaseIfClaimedBy(id, claimer));
        return n == null ? -1 : n;
    }

    private UUID newAlert() {
        AdminAlertEntity a = new AdminAlertEntity();
        a.setType("CLAIM_IT_" + UUID.randomUUID().toString().substring(0, 8));
        return repo.saveAndFlush(a).getId();
    }

    @Test
    @DisplayName("deux prises successives par deux admins : 1 ligne puis 0")
    void sequentialClaims_oneThenZero() {
        UUID id = newAlert();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThat(claim(id, a, now)).isEqualTo(1);
        assertThat(claim(id, b, now)).isZero();
        assertThat(claim(id, a, now)).isZero();

        AdminAlertEntity stored = repo.findById(id).orElseThrow();
        assertThat(stored.getClaimedBy()).isEqualTo(a);
        assertThat(stored.isResolved()).isFalse();
    }

    @Test
    @DisplayName("libération : seulement par le détenteur indiqué")
    void release_onlyByClaimer() {
        UUID id = newAlert();
        UUID a = UUID.randomUUID();
        tx.execute(s -> repo.claimIfFree(id, a, OffsetDateTime.now(ZoneOffset.UTC)));

        assertThat(release(id, UUID.randomUUID())).isZero();
        assertThat(release(id, a)).isEqualTo(1);
        assertThat(repo.findById(id).orElseThrow().getClaimedBy()).isNull();
    }

    @Test
    @DisplayName("deux admins simultanés : exactement un gagnant")
    void concurrentClaims_exactlyOneWinner() throws Exception {
        for (int round = 0; round < 10; round++) {
            UUID id = newAlert();
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Integer> fa = pool.submit(() -> attempt(id, a, ready, go));
                Future<Integer> fb = pool.submit(() -> attempt(id, b, ready, go));
                ready.await();
                go.countDown();
                assertThat((int) fa.get() + (int) fb.get()).isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
            UUID owner = repo.findById(id).orElseThrow().getClaimedBy();
            assertThat(owner).isIn(a, b);
        }
    }

    private Integer attempt(UUID id, UUID admin, CountDownLatch ready, CountDownLatch go) throws Exception {
        ready.countDown();
        go.await();
        return tx.execute(s -> repo.claimIfFree(id, admin, OffsetDateTime.now(ZoneOffset.UTC)));
    }
}
