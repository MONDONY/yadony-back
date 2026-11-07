package com.yadony.api.alerts;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.notifications.NotificationPrefsJpaRepository;
import com.yadony.api.notifications.NotificationPrefsService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V362 sur Postgres embarqué, toutes migrations Flyway et ddl-auto=validate : l'entité colle au
 * schéma, l'index unique (villes insensibles à la casse) tient, et les purges passent malgré la
 * clé étrangère vers {@code users}.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Suggestions selon les recherches : schéma V362 (Postgres)")
class SearchInterestPostgresIT {

    private static EmbeddedPostgres postgres;

    @Autowired SearchInterestRepository repository;
    @Autowired UserRepository userRepository;
    @Autowired NotificationPrefsService prefsService;
    @Autowired NotificationPrefsJpaRepository prefsRepository;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) postgres.close();
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

    private UUID persistUser() {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-search-" + UUID.randomUUID());
        u.setFirstName("Awa");
        u.setLastName("Koné");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setTotalTrips(0);
        return userRepository.save(u).getId();
    }

    private SearchInterestEntity interest(UUID userId, String dep, String arr, LocalDateTime searchedAt) {
        SearchInterestEntity s = new SearchInterestEntity();
        s.setUserId(userId);
        s.setDepartureCity(dep);
        s.setArrivalCity(arr);
        s.setResultCount(0);
        s.setLastSearchedAt(searchedAt);
        return s;
    }

    @Test
    @DisplayName("une seule ligne par utilisateur et par corridor, casse comprise")
    void oneRowPerUserAndCorridor() {
        UUID userId = persistUser();
        LocalDateTime now = LocalDateTime.of(2026, 11, 7, 10, 0);
        repository.saveAndFlush(interest(userId, "Paris", "Abidjan", now));

        assertThatThrownBy(() -> repository.saveAndFlush(interest(userId, "PARIS", "abidjan", now)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findForCorridor(userId, "paris", "ABIDJAN")).isPresent();
        assertThat(repository.findPendingForCorridor("PARIS", "abidjan", now.minusDays(30))).hasSize(1);
    }

    @Test
    @DisplayName("purges : recherches anciennes, puis tout le compte")
    void purgesWorkDespiteTheForeignKey() {
        UUID userId = persistUser();
        LocalDateTime now = LocalDateTime.of(2026, 11, 7, 10, 0);
        repository.saveAndFlush(interest(userId, "Lyon", "Dakar", now.minusDays(45)));
        repository.saveAndFlush(interest(userId, "Lyon", "Bamako", now));

        assertThat(repository.purgeSearchedBefore(now.minusDays(30))).isEqualTo(1);
        assertThat(repository.purgeByUserId(userId)).isEqualTo(1);
        assertThat(repository.findAllByUserIdOrderByLastSearchedAtDesc(userId)).isEmpty();
    }

    @Test
    @DisplayName("le réglage des suggestions existe, allumé par défaut, et se coupe")
    void searchSuggestionsColumn() {
        UUID userId = persistUser();

        prefsService.disableSearchSuggestions(userId);

        assertThat(prefsRepository.findById(userId)).get()
                .satisfies(p -> {
                    assertThat(p.isSearchSuggestions()).isFalse();
                    assertThat(p.isPushCorridorAlerts()).isTrue();
                });
    }
}
