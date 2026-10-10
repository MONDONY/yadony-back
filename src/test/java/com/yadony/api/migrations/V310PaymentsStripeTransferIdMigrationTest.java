package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V310 : identifiant du Transfer Stripe du versement, colonne facultative sur {@code payments}. */
class V310PaymentsStripeTransferIdMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("310").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    void v310_ajouteUneColonneTexteFacultative() {
        Map<String, Object> column = new JdbcTemplate(dataSource).queryForMap("""
                SELECT data_type, is_nullable, character_maximum_length
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'payments' AND column_name = 'stripe_transfer_id'
                """);
        assertThat(column.get("data_type")).isEqualTo("character varying");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(((Number) column.get("character_maximum_length")).intValue()).isEqualTo(255);
    }

    @Test
    void v310_derniereVersionAppliquee() {
        Integer max = new JdbcTemplate(dataSource).queryForObject(
                "SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history WHERE success", Integer.class);
        assertThat(max).isEqualTo(310);
    }
}
