package com.yadony.api.cancellation.fee;

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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V393 sur PostgreSQL embarqué : toutes les migrations Flyway puis {@code ddl-auto=validate}.
 * Le démarrage du contexte prouve que l'entité colle à la table ; on vérifie aussi que l'unicité
 * par colis est un index partiel (ignore les lignes supprimées).
 */
@SpringBootTest
@ActiveProfiles("e2e")
class CancellationFeePostgresMigrationIT {

    private static EmbeddedPostgres postgres;

    @Autowired JdbcTemplate jdbc;
    @Autowired CancellationFeeRepository repository;

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

    @Test
    void uniciteParColis_estUnIndexPartielSurLesLignesActives() {
        List<String> defs = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_cancellation_fees_bid'", String.class);

        assertThat(defs).hasSize(1);
        assertThat(defs.get(0)).contains("UNIQUE").contains("deleted_at IS NULL");
        assertThat(repository.findByBidId(java.util.UUID.randomUUID())).isEmpty();
    }
}
