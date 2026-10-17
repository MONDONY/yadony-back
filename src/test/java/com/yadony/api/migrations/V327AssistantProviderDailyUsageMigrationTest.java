package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V327AssistantProviderDailyUsageMigrationTest {

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

    private static String constraintDefinition(String name) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '" + name + "'");
            assertThat(rs.next()).as("la contrainte %s doit exister", name).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void v327_creeLaTableDesCompteursDuJour() throws Exception {
        Flyway baseline = flywayUpTo("326");
        baseline.clean();
        baseline.migrate();
        flywayUpTo("327").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = 'assistant_provider_daily_usage' ORDER BY column_name");
            List<String> columns = new ArrayList<>();
            while (rs.next()) columns.add(rs.getString(1));
            assertThat(columns).containsExactly("provider_id", "requests", "successes", "updated_at", "usage_date");
        }
        assertThat(constraintDefinition("pk_assistant_provider_daily_usage")).contains("PRIMARY KEY (provider_id, usage_date)");
        assertThat(constraintDefinition("chk_assistant_provider_daily_usage_counts")).contains("requests >= 0");

        UUID provider = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO assistant_llm_providers (id, name, slug, base_url, model, api_key_encrypted, "
                    + "api_key_last4, priority, created_at, updated_at) VALUES ('" + provider + "', 'Claude', 'claude', "
                    + "'https://api.anthropic.com', 'm', 'x', '1234', 1, NOW(), NOW())");
            s.execute("INSERT INTO assistant_provider_daily_usage (provider_id, usage_date, updated_at) "
                    + "VALUES ('" + provider + "', DATE '2026-10-16', NOW())");
            ResultSet rs = s.executeQuery("SELECT requests, successes FROM assistant_provider_daily_usage");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
            assertThat(rs.getInt(2)).isZero();
            assertThatThrownBy(() -> s.execute("INSERT INTO assistant_provider_daily_usage (provider_id, usage_date, "
                    + "updated_at) VALUES ('" + UUID.randomUUID() + "', DATE '2026-10-16', NOW())"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("foreign key");
        }
    }
}
