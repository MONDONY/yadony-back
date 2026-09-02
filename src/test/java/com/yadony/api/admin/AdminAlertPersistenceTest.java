package com.yadony.api.admin;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tourne sur un PostgreSQL embarqué avec le vrai Flyway : la colonne
 * {@code admin_alerts.payload} y est réellement {@code jsonb}, contrairement au
 * schéma H2 généré des autres tests, laxiste sur les types.
 *
 * <p>Régression du 2026-09-02 : {@code payload} (String) sans
 * {@code @JdbcTypeCode(SqlTypes.JSON)} — Hibernate 6 envoyait un {@code varchar},
 * Postgres refusait l'INSERT, et toutes les alertes DB des schedulers (escrow
 * J+48, échéances de retour) échouaient en boucle. Aucun test ne l'attrapait :
 * les schedulers sont testés sur mocks, et H2 avalait l'INSERT.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("AdminAlertPersistenceTest — payload JSON écrit dans la colonne jsonb Postgres")
class AdminAlertPersistenceTest {

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

    @Autowired AdminAlertRepository adminAlertRepository;
    @Autowired TestEntityManager entityManager;

    @Test
    @DisplayName("save écrit le JSON en jsonb et le relit intact (chemin des schedulers)")
    void save_persistsJsonPayload_intoJsonbColumn() {
        AdminAlertEntity alert = new AdminAlertEntity();
        alert.setType("RETURN_DEADLINE_EXPIRED");
        alert.setPayload("{\"bidId\":\"5d6dd32e-8211-498f-bf27-78892683136e\",\"senderId\":\"s1\"}");
        alert.setResolved(false);

        adminAlertRepository.saveAndFlush(alert);
        entityManager.clear();

        AdminAlertEntity reloaded = adminAlertRepository.findById(alert.getId()).orElseThrow();
        assertThat(reloaded.getPayload()).contains("5d6dd32e-8211-498f-bf27-78892683136e");
    }
}
