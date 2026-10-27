package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Rappel automatique d'un fil de négociation : colonne facultative, absente pour les fils existants. */
class V351NegotiationThreadAutoReminderMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    @Test
    void migrate_addsNullableAutoReminderTimestamp() throws Exception {
        Flyway baseline = flywayUpTo("350");
        baseline.clean();
        baseline.migrate();
        flywayUpTo("351").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT data_type, is_nullable "
                    + "FROM information_schema.columns WHERE table_schema = 'public' "
                    + "AND table_name = 'negotiation_threads' AND column_name = 'auto_reminder_at'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("timestamp without time zone");
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");
        }
    }

    @Test
    void migrate_isIdempotent() throws Exception {
        Flyway baseline = flywayUpTo("350");
        baseline.clean();
        baseline.migrate();
        flywayUpTo("351").migrate();

        // Rejouer le DDL ne doit pas échouer (IF NOT EXISTS).
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE negotiation_threads ADD COLUMN IF NOT EXISTS auto_reminder_at TIMESTAMP NULL");
        }
    }
}
