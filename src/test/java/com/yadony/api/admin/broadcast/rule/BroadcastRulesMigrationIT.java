package com.yadony.api.admin.broadcast.rule;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Migration V363 : segments de diffusion et règle d'audience sur l'historique. */
@SpringBootTest
@ActiveProfiles("e2e")
@Transactional
class BroadcastRulesMigrationIT {

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
    }

    @Autowired JdbcTemplate jdbc;

    private void insertSegment(String name, boolean deleted) {
        UUID admin = UUID.randomUUID();
        jdbc.update("INSERT INTO broadcast_segments (name, rule, created_by, updated_by, deleted_at) "
                + "VALUES (?, '{\"op\":\"AND\",\"children\":[]}'::jsonb, ?, ?, "
                + (deleted ? "now()" : "NULL") + ")", name, admin, admin);
    }

    @Test
    void deuxSegmentsActifsAuMemeNomIgnorantLaCasseSontRefuses() {
        insertSegment("Pro sans Stripe", false);
        assertThatThrownBy(() -> insertSegment("pro SANS stripe", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unNomPeutEtreReutiliseApresSuppressionLogique() {
        insertSegment("Pro sans Stripe", true);
        insertSegment("Pro sans Stripe", false);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM broadcast_segments", Integer.class);
        assertThat(n).isEqualTo(2);
    }

    @Test
    void unEnvoiParRegleSInsere() {
        jdbc.update("INSERT INTO admin_broadcasts (title, body, target_type, audience_rule, admin_id) "
                + "VALUES ('t', 'b', 'RULE', '{\"op\":\"AND\",\"children\":[]}'::jsonb, ?)", UUID.randomUUID());
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM admin_broadcasts WHERE target_type = 'RULE' AND audience_rule IS NOT NULL",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }
}
