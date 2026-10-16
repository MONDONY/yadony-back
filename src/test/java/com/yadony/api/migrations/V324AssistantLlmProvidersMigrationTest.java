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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class V324AssistantLlmProvidersMigrationTest {

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
            ResultSet rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = '" + name + "'");
            assertThat(rs.next()).as("la contrainte %s doit exister", name).isTrue();
            return rs.getString(1);
        }
    }

    private static boolean indexExists(String name) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT 1 FROM pg_indexes WHERE indexname = '" + name + "'");
            return rs.next();
        }
    }

    @Test
    void v324_creeLaTableDesFournisseurs() throws Exception {
        Flyway baseline = flywayUpTo("323");
        baseline.clean();
        baseline.migrate();
        flywayUpTo("324").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = 'assistant_llm_providers' ORDER BY column_name");
            List<String> columns = new ArrayList<>();
            while (rs.next()) columns.add(rs.getString(1));
            assertThat(columns).contains("name", "slug", "base_url", "model", "api_key_encrypted", "api_key_last4",
                    "supports_json_mode", "daily_request_limit", "priority", "enabled", "created_at", "updated_at", "deleted_at");
        }
        assertThat(indexExists("uq_assistant_llm_providers_slug")).isTrue();
        assertThat(constraintDefinition("chk_assistant_provider_daily_limit")).contains("daily_request_limit > 0");
        assertThat(constraintDefinition("chk_assistant_provider_base_url")).contains("https://");
    }
}
