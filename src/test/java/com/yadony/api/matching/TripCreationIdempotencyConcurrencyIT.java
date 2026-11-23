package com.yadony.api.matching;

import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.AnnouncementResponse;
import com.yadony.api.matching.dto.TripRequest;
import com.yadony.api.matching.dto.TripResponse;
import com.yadony.api.matching.events.AnnouncementCreatedEvent;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deux créations simultanées de même {@code Idempotency-Key}, sur une vraie base (PostgreSQL embarqué, toutes
 * migrations, chaque requête commite pour de vrai) : l'index unique partiel de V400 n'en laisse passer qu'une,
 * la perdante relit le trajet de la gagnante et le rend en rejeu. Un seul trajet, un seul audit.
 *
 * <p>Déterministe, sans {@code sleep} pour ordonner : la première requête insère son trajet (insertion
 * immédiate, clé comprise) puis s'arrête à son événement {@link AnnouncementCreatedEvent}, transaction
 * ouverte ; on lance la seconde, on attend qu'elle soit bloquée sur un verrou (l'entrée d'index non
 * validée de la première, ou la ligne de l'utilisateur quand une limite mensuelle s'applique), puis on
 * libère la première.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Idempotency-Key : deux créations simultanées, un seul trajet (PostgreSQL)")
class TripCreationIdempotencyConcurrencyIT {

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

    /** Arrête la PREMIÈRE création qui publie son événement, transaction ouverte, jusqu'à {@link #release}. */
    static class FirstCreationPause {
        private final AtomicBoolean armed = new AtomicBoolean();
        private volatile CountDownLatch reached = new CountDownLatch(1);
        private volatile CountDownLatch release = new CountDownLatch(1);

        void arm() {
            reached = new CountDownLatch(1);
            release = new CountDownLatch(1);
            armed.set(true);
        }

        void release() {
            armed.set(false);
            release.countDown();
        }

        boolean awaitReached() throws InterruptedException {
            return reached.await(60, TimeUnit.SECONDS);
        }

        @EventListener
        public void onCreated(AnnouncementCreatedEvent event) {
            if (armed.compareAndSet(true, false)) {
                reached.countDown();
                try {
                    if (!release.await(120, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("première création jamais libérée par le test");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    @TestConfiguration
    static class PauseConfig {
        @Bean
        FirstCreationPause firstCreationPause() {
            return new FirstCreationPause();
        }
    }

    @Autowired TripCreationIdempotency idempotency;
    @Autowired FirstCreationPause pause;
    @Autowired JdbcTemplate jdbc;

    private UUID userId;
    private String uid;

    @BeforeEach
    void seedTraveler() {
        userId = UUID.randomUUID();
        uid = "uid-idem-race-" + userId;
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, username, status, kyc_status, gp_pro, is_pro_account)
                VALUES (?, ?, ?, 'ACTIVE', 'VERIFIED', false, true)
                """, userId, uid, ("r" + userId.toString().replace("-", "")).substring(0, 20));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'TRAVELER')", userId);
    }

    private static AnnouncementRequest leg(String from, String to, String fromCountry, String toCountry,
                                           LocalDate date) {
        return new AnnouncementRequest(from, to, date, LocalTime.of(10, 0), null,
                new AddressDto(from, 48.85, 2.35), new AddressDto(to, 14.69, -17.44),
                new BigDecimal("10"), new BigDecimal("8"),
                TransportMode.PLANE, null, null, null, Set.of(PaymentMethod.CASH), CapacityUnit.KG_EXACT, null,
                fromCountry, toCountry, date.atTime(7, 30), false, false, "EUR", null, null, null, null,
                null, null, null);
    }

    private static AnnouncementRequest direct() {
        return leg("Paris", "Dakar", "FR", "SN", LocalDate.now().plusDays(12));
    }

    private static TripRequest twoLegs() {
        LocalDate d = LocalDate.now().plusDays(12);
        return new TripRequest(List.of(
                leg("Paris", "Dakar", "FR", "SN", d),
                leg("Dakar", "Bamako", "SN", "ML", d.plusDays(3))));
    }

    private int tripsOfTraveler() {
        return jdbc.queryForObject("SELECT count(*) FROM announcements WHERE traveler_id = ?", Integer.class, userId);
    }

    private String keyOf(UUID announcementId) {
        return jdbc.queryForObject("SELECT idempotency_key FROM announcements WHERE id = ?", String.class,
                announcementId);
    }

    private int createdAudits() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'ANNOUNCEMENT_CREATED'"
                + " AND entity_id = ?", Integer.class, userId);
    }

    /** Lance {@code first}, l'attend à son événement, lance {@code second}, attend qu'elle bloque, libère. */
    private <T> List<TripCreationIdempotency.Outcome<T>> race(Callable<TripCreationIdempotency.Outcome<T>> first,
                                                              Callable<TripCreationIdempotency.Outcome<T>> second)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pause.arm();
        try {
            Future<TripCreationIdempotency.Outcome<T>> a = pool.submit(first);
            assertThat(pause.awaitReached()).as("la première création a inséré son trajet").isTrue();
            Future<TripCreationIdempotency.Outcome<T>> b = pool.submit(second);
            awaitBlockedOnLock(b);
            pause.release();
            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally {
            pause.release();
            pool.shutdownNow();
        }
    }

    private void awaitBlockedOnLock(Future<?> second) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            assertThat(second.isDone())
                    .as("la seconde création ne doit pas finir tant que la première n'a pas validé")
                    .isFalse();
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                            + "AND datname = current_database()", Integer.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("La seconde création n'a jamais attendu de verrou");
    }

    @Test
    @DisplayName("POST /announcements : l'index unique tranche, la perdante rend le trajet de la gagnante")
    void twoSimultaneousCreations_oneTrip() throws Exception {
        String key = UUID.randomUUID().toString();

        List<TripCreationIdempotency.Outcome<AnnouncementResponse>> outcomes = race(
                () -> idempotency.createAnnouncement(uid, direct(), key),
                () -> idempotency.createAnnouncement(uid, direct(), key));

        assertThat(outcomes.get(0).replayed()).as("la gagnante crée").isFalse();
        assertThat(outcomes.get(1).replayed()).as("la perdante rejoue").isTrue();
        assertThat(outcomes.get(1).body().id()).isEqualTo(outcomes.get(0).body().id());
        assertThat(tripsOfTraveler()).isEqualTo(1);
        assertThat(keyOf(outcomes.get(0).body().id())).isEqualTo(key);
        assertThat(createdAudits()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /announcements, limite mensuelle de 1 : la perdante, refusée par la limite que la gagnante vient d'atteindre, rejoue")
    void twoSimultaneousCreations_atTheMonthlyLimit_oneTripAndAReplay() throws Exception {
        jdbc.update("UPDATE users SET monthly_trip_limit = 1, traveler_status = 'INDIVIDUAL' WHERE id = ?", userId);
        String key = UUID.randomUUID().toString();

        List<TripCreationIdempotency.Outcome<AnnouncementResponse>> outcomes = race(
                () -> idempotency.createAnnouncement(uid, direct(), key),
                () -> idempotency.createAnnouncement(uid, direct(), key));

        assertThat(outcomes.get(0).replayed()).isFalse();
        assertThat(outcomes.get(1).replayed()).isTrue();
        assertThat(outcomes.get(1).body().id()).isEqualTo(outcomes.get(0).body().id());
        assertThat(tripsOfTraveler()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /announcements/trips : un seul voyage, la perdante rend ses étapes dans l'ordre")
    void twoSimultaneousTrips_oneTrip() throws Exception {
        String key = UUID.randomUUID().toString();

        List<TripCreationIdempotency.Outcome<TripResponse>> outcomes = race(
                () -> idempotency.createTrip(uid, twoLegs(), key),
                () -> idempotency.createTrip(uid, twoLegs(), key));

        TripResponse winner = outcomes.get(0).body();
        TripResponse replay = outcomes.get(1).body();
        assertThat(outcomes.get(0).replayed()).isFalse();
        assertThat(outcomes.get(1).replayed()).isTrue();
        assertThat(replay.tripGroupId()).isEqualTo(winner.tripGroupId());
        assertThat(replay.legs()).extracting(AnnouncementResponse::id)
                .containsExactlyElementsOf(winner.legs().stream().map(AnnouncementResponse::id).toList());
        assertThat(tripsOfTraveler()).isEqualTo(2);
        assertThat(keyOf(winner.legs().get(0).id())).isEqualTo(key);
        assertThat(keyOf(winner.legs().get(1).id())).isNull();
    }
}
