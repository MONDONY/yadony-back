package com.yadony.api.likes;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.city.GeoNamesDataLoader;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * J'aime simultanés sur un vrai Postgres (index unique partiel {@code ux_trip_likes_active},
 * absent de H2). Pas de {@code @Transactional} : chaque appel doit commiter pour de vrai.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class TripLikeConcurrencyIT {

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

    @Autowired private TripLikeService tripLikeService;
    @MockitoBean private GeoNamesDataLoader geoNamesDataLoader;
    @Autowired private UserRepository userRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void like_20_fois_simultanees_du_meme_utilisateur_donne_une_ligne_sans_erreur() throws Exception {
        UUID userId = persistUser().getId();
        UUID tripId = persistTrip();

        runConcurrently(20, i -> tripLikeService.like(userId, tripId));

        assertThat(rows(tripId)).isEqualTo(1);
        assertThat(tripLikeService.like(userId, tripId).likeCount()).isEqualTo(1);
    }

    @Test
    void like_de_20_utilisateurs_differents_donne_un_compte_de_20() throws Exception {
        UUID tripId = persistTrip();
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            users.add(persistUser().getId());
        }

        runConcurrently(20, i -> tripLikeService.like(users.get(i), tripId));

        assertThat(rows(tripId)).isEqualTo(20);
        assertThat(tripLikeService.countsFor(List.of(tripId))).containsEntry(tripId, 20L);
        assertThat(tripLikeService.likedBy(users.get(0), List.of(tripId))).containsExactly(tripId);
    }

    @Test
    void unlike_20_fois_simultanees_laisse_zero_ligne_sans_erreur() throws Exception {
        UUID userId = persistUser().getId();
        UUID tripId = persistTrip();
        tripLikeService.like(userId, tripId);

        runConcurrently(20, i -> tripLikeService.unlike(userId, tripId));

        assertThat(rows(tripId)).isZero();
    }

    @FunctionalInterface
    private interface Call {
        void run(int index);
    }

    private void runConcurrently(int threads, Call call) throws Exception {
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                calls.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    call.run(index);
                    return null;
                }));
            }
            for (Future<?> f : calls) {
                assertThatCode(() -> f.get(30, TimeUnit.SECONDS)).doesNotThrowAnyException();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private int rows(UUID tripId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trip_likes WHERE trip_id = ? AND deleted_at IS NULL", Integer.class, tripId);
        return count == null ? 0 : count;
    }

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("trip-like-concurrency-it-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(Set.of(Role.SENDER));
        user.setStripeAccountStatus(StripeAccountStatus.NOT_CREATED);
        return userRepository.saveAndFlush(user);
    }

    private UUID persistTrip() {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(persistUser().getId());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Bamako");
        a.setDepartureDate(LocalDate.now().plusDays(5));
        a.setTransportMode(TransportMode.PLANE);
        a.setPickupAddressLabel("Gare du Nord, Paris");
        a.setPickupLat(new BigDecimal("48.880756"));
        a.setPickupLng(new BigDecimal("2.354987"));
        a.setDeliveryAddressLabel("Aéroport Bamako-Sénou");
        a.setDeliveryLat(new BigDecimal("12.533579"));
        a.setDeliveryLng(new BigDecimal("-7.948969"));
        a.setAvailableKg(new BigDecimal("20.00"));
        a.setTotalKg(new BigDecimal("23.00"));
        a.setPricePerKg(new BigDecimal("8.00"));
        a.setTimezone("Europe/Paris");
        a.setStatus(AnnouncementStatus.ACTIVE);
        return announcementRepository.saveAndFlush(a).getId();
    }
}
