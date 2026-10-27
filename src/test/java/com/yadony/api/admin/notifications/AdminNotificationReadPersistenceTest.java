package com.yadony.api.admin.notifications;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tourne sur un PostgreSQL embarqué avec le vrai Flyway ({@code ddl-auto: validate}) : la table
 * {@code admin_notification_reads} de V352 doit correspondre à l'entité, et sa contrainte d'unicité
 * garder une seule ligne par administrateur et notification.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("AdminNotificationReadPersistenceTest : V352 sur PostgreSQL")
class AdminNotificationReadPersistenceTest {

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

    @Autowired AdminNotificationReadRepository repository;
    @Autowired TestEntityManager entityManager;

    private AdminNotificationReadEntity read(UUID admin, String notificationId, LocalDateTime at) {
        AdminNotificationReadEntity read = new AdminNotificationReadEntity();
        read.setAdminId(admin);
        read.setNotificationId(notificationId);
        read.setReadAt(at);
        return read;
    }

    @Test
    @DisplayName("save puis relecture : existsBy et findReadIds voient la lecture, chaque administrateur la sienne")
    void saveAndRead_perAdmin() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String id = "REPORT_CREATED:" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        repository.saveAndFlush(read(me, id, now));
        entityManager.clear();

        assertThat(repository.existsByAdminIdAndNotificationId(me, id)).isTrue();
        assertThat(repository.existsByAdminIdAndNotificationId(other, id)).isFalse();
        assertThat(repository.findReadIds(me, now.minusDays(30))).containsExactly(id);
        assertThat(repository.findReadIds(other, now.minusDays(30))).isEmpty();
    }

    @Test
    @DisplayName("findReadIds ignore les lectures plus anciennes que la fenêtre")
    void findReadIds_respectsWindow() {
        UUID me = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        String recent = "ADMIN_ALERT:" + UUID.randomUUID();
        String old = "ADMIN_ALERT:" + UUID.randomUUID();
        repository.saveAndFlush(read(me, recent, now.minusDays(1)));
        repository.saveAndFlush(read(me, old, now.minusDays(45)));
        entityManager.clear();

        assertThat(repository.findReadIds(me, now.minusDays(30))).containsExactly(recent);
    }

    @Test
    @DisplayName("une seule ligne par administrateur et notification : le doublon est refusé par la base")
    void uniqueConstraint_refusesDuplicate() {
        UUID me = UUID.randomUUID();
        String id = "PAYOUT_HELD:" + UUID.randomUUID();
        repository.saveAndFlush(read(me, id, LocalDateTime.now()));

        assertThatThrownBy(() -> repository.saveAndFlush(read(me, id, LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
