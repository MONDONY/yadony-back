package com.yadony.api.travelerstatus;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.AnnouncementStatus;
import com.yadony.api.matching.TransportMode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Course entre deux publications simultanées, sur une vraie base (Postgres embarqué, chaque thread commite
 * pour de vrai). La limite est à 1 et rien n'est publié : les deux publications voient « 0 utilisé » si le
 * garde ne verrouille pas la ligne de l'utilisateur AVANT de compter. Avec le verrou, la seconde attend la
 * validation de la première, recompte et est refusée.
 *
 * <p>Déterministe, sans {@code sleep} : la première transaction passe le garde puis attend ; on lance la
 * seconde, on attend qu'elle ait fini (sans verrou) ou qu'une session soit bloquée sur un verrou de ligne
 * (avec verrou), puis on libère la première.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class PublishingLimitConcurrencyIT {

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

    @Autowired PublishingLimitGate gate;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private UUID persistUser(Integer limit, boolean gpPro) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-race-" + suffix);
        u.setUsername("race_" + suffix);
        u.setStatus(UserStatus.ACTIVE);
        u.setRoles(Set.of(Role.TRAVELER));
        u.setKycStatus(KycStatus.VERIFIED);
        u.setMonthlyTripLimit(limit);
        u.setGpPro(gpPro);
        u.setTravelerStatus(TravelerStatus.INDIVIDUAL);
        return userRepository.saveAndFlush(u).getId();
    }

    private void insertPublishedTrip(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setArrivalCountryCode("SN");
        a.setDepartureDate(LocalDate.now().plusDays(20));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Paris");
        a.setPickupLat(new BigDecimal("48.860000"));
        a.setPickupLng(new BigDecimal("2.350000"));
        a.setDeliveryAddressLabel("Dakar");
        a.setDeliveryLat(new BigDecimal("14.693000"));
        a.setDeliveryLng(new BigDecimal("-17.447000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(AnnouncementStatus.ACTIVE);
        announcementRepository.saveAndFlush(a);
    }

    /** Ce que fait une publication : garde puis insertion, dans la même transaction. */
    private boolean publish(UUID userId, Runnable afterGate) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            tx.executeWithoutResult(status -> {
                UserEntity u = userRepository.findById(userId).orElseThrow();
                gate.assertCanPublish(u);
                afterGate.run();
                insertPublishedTrip(userId);
            });
            return true;
        } catch (YadonyBusinessException e) {
            assertThat(e.getErrorCode()).isEqualTo("monthly-limit-reached");
            return false;
        }
    }

    private int tripsOf(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM announcements WHERE traveler_id = ?", Integer.class, userId);
    }

    @Test
    void deuxPublicationsSimultanees_limiteAUn_uneSeuleReussit() throws Exception {
        UUID userId = persistUser(1, false);
        runRace(userId);

        assertThat(tripsOf(userId)).isEqualTo(1);
    }

    @Test
    void gpProValide_avecLimiteSaisie_lesDeuxPublicationsReussissent() throws Exception {
        UUID userId = persistUser(1, true);
        CountDownLatch firstPassed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> publish(userId, () -> {
                firstPassed.countDown();
                await(release);
            }));
            assertThat(firstPassed.await(60, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = pool.submit(() -> publish(userId, () -> { }));
            // Aucun verrou pour un GP Pro : la seconde finit sans attendre la première.
            assertThat(second.get(60, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            assertThat(first.get(60, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(tripsOf(userId)).isEqualTo(2);
    }

    private void runRace(UUID userId) throws Exception {
        CountDownLatch firstPassed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> publish(userId, () -> {
                firstPassed.countDown();
                await(release);
            }));
            assertThat(firstPassed.await(60, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = pool.submit(() -> publish(userId, () -> { }));
            awaitDoneOrBlockedOnLock(second);
            release.countDown();
            boolean firstOk = first.get(60, TimeUnit.SECONDS);
            boolean secondOk = second.get(60, TimeUnit.SECONDS);
            assertThat(firstOk).as("la première publication, passée sous la limite").isTrue();
            assertThat(secondOk).as("la seconde, refusée une fois la première validée").isFalse();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private void awaitDoneOrBlockedOnLock(Future<Boolean> second) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            if (second.isDone()) {
                return;
            }
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                            + "AND datname = current_database()", Integer.class);
            if (waiting != null && waiting >= 1) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("La seconde publication n'a ni fini ni attendu un verrou");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(120, TimeUnit.SECONDS)) {
                throw new IllegalStateException("verrou jamais libéré par le test");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
