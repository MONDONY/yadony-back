package com.yadony.api.admin.broadcast;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lot D — l'entite doit s'accorder avec V221 : Flyway est actif et {@code ddl-auto=validate},
 * donc tout ecart colonne/type fait echouer le demarrage du contexte, pas une assertion.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AdminBroadcastRepositoryTest {

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

    @Autowired
    private AdminBroadcastRepository repository;

    @Test
    void persistsAllTargetingColumns() {
        UUID adminId = UUID.randomUUID();
        UUID targetUserId = UUID.randomUUID();

        AdminBroadcastEntity saved = repository.saveAndFlush(new AdminBroadcastEntity(
                "Maintenance", "Service indisponible ce soir de 22h a 23h.",
                BroadcastTargetType.CORRIDOR, "Paris", "Dakar", targetUserId, 42, adminId));

        AdminBroadcastEntity reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getTitle()).isEqualTo("Maintenance");
        assertThat(reloaded.getBody()).isEqualTo("Service indisponible ce soir de 22h a 23h.");
        assertThat(reloaded.getTargetType()).isEqualTo(BroadcastTargetType.CORRIDOR);
        assertThat(reloaded.getTargetOrigin()).isEqualTo("Paris");
        assertThat(reloaded.getTargetDestination()).isEqualTo("Dakar");
        assertThat(reloaded.getTargetUserId()).isEqualTo(targetUserId);
        assertThat(reloaded.getRecipientCount()).isEqualTo(42);
        assertThat(reloaded.getAdminId()).isEqualTo(adminId);
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    @Test
    void historyIsReturnedMostRecentFirst() {
        UUID adminId = UUID.randomUUID();
        repository.saveAndFlush(new AdminBroadcastEntity(
                "Ancien", "corps", BroadcastTargetType.ALL, null, null, null, 1, adminId));
        repository.saveAndFlush(new AdminBroadcastEntity(
                "Recent", "corps", BroadcastTargetType.SENDERS, null, null, null, 2, adminId));

        var page = repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent().get(0).getRecipientCount()).isEqualTo(2);
    }

    @Test
    void softDeletedRowsAreHidden() {
        AdminBroadcastEntity saved = repository.saveAndFlush(new AdminBroadcastEntity(
                "A supprimer", "corps", BroadcastTargetType.ALL, null, null, null, 0, UUID.randomUUID()));
        saved.softDelete();
        repository.saveAndFlush(saved);

        assertThat(repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 20)).getTotalElements())
                .isZero();
    }
}
