package com.yadony.api.favorites;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

/**
 * Ajouts simultanés du même favori, sur un vrai Postgres (index unique partiel
 * {@code ux_favorites_active} de V152, absent de H2).
 *
 * <p>Régression du test de charge k6 du 07/10 sur staging : 40 VUs qui ajoutent le même
 * favori, 16 % des PUT en 500. L'ancien {@code save()} + catch de
 * {@code DataIntegrityViolationException} ne protégeait rien, l'INSERT partant au commit
 * de la transaction du service, hors du try.
 *
 * <p>Pas de {@code @Transactional} sur la classe : chaque appel au service doit commiter
 * pour de vrai, c'est au commit que la violation se produisait.
 */
@SpringBootTest
@ActiveProfiles("e2e")
class FavoriteConcurrencyIT {

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

    @Autowired private FavoriteService favoriteService;
    @MockitoSpyBean private FavoriteRepository favoriteRepository;
    // Import de dizaines de milliers de villes au démarrage, inutile ici.
    @MockitoBean private GeoNamesDataLoader geoNamesDataLoader;
    @Autowired private UserRepository userRepository;
    @Autowired private AnnouncementRepository announcementRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    void addFavorite_rowInsertedBetweenCheckAndInsert_isNoOp() {
        String uid = persistUser().getFirebaseUid();
        UUID tripId = persistTrip();
        favoriteService.addFavorite(uid, FavoriteTargetType.TRIP, tripId);

        // Fige la course : la vérification préalable ne voit pas la ligne déjà commitée,
        // exactement comme la seconde de deux requêtes simultanées.
        doReturn(false).when(favoriteRepository)
                .existsByUserIdAndTargetTypeAndTargetId(any(), any(), any());

        assertThatCode(() -> favoriteService.addFavorite(uid, FavoriteTargetType.TRIP, tripId))
                .doesNotThrowAnyException();
        assertThat(activeRows(tripId)).isEqualTo(1);
    }

    @Test
    void addFavorite_concurrentRequests_allSucceedWithSingleRow() throws Exception {
        String uid = persistUser().getFirebaseUid();
        UUID tripId = persistTrip();
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    favoriteService.addFavorite(uid, FavoriteTargetType.TRIP, tripId);
                    return null;
                }));
            }
            for (Future<?> call : calls) {
                assertThatCode(() -> call.get(30, TimeUnit.SECONDS)).doesNotThrowAnyException();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(activeRows(tripId)).isEqualTo(1);
    }

    @Test
    void removeFavorite_concurrentRequests_allSucceedAndRowIsGone() throws Exception {
        UserEntity user = persistUser();
        UUID tripId = persistTrip();
        favoriteService.addFavorite(user.getFirebaseUid(), FavoriteTargetType.TRIP, tripId);
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                calls.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    favoriteService.removeFavorite(user.getId(), FavoriteTargetType.TRIP, tripId);
                    return null;
                }));
            }
            for (Future<?> call : calls) {
                assertThatCode(() -> call.get(30, TimeUnit.SECONDS)).doesNotThrowAnyException();
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(activeRows(tripId)).isZero();
    }

    private int activeRows(UUID tripId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM favorites WHERE target_id = ? AND deleted_at IS NULL",
                Integer.class, tripId);
        return count == null ? 0 : count;
    }

    private UserEntity persistUser() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("favorite-concurrency-it-" + UUID.randomUUID());
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
