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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V329AssistantUserDailyUsageMigrationTest {

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
    void v329_creeLaTableDesQuestionsDuJour() throws Exception {
        Flyway baseline = flywayUpTo("328");
        baseline.clean();
        baseline.migrate();
        flywayUpTo("329").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = 'assistant_user_daily_usage' ORDER BY column_name");
            List<String> columns = new ArrayList<>();
            while (rs.next()) columns.add(rs.getString(1));
            assertThat(columns).containsExactly("firebase_uid", "questions", "updated_at", "usage_date");

            rs = s.executeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_assistant_user_daily_usage_date'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("(usage_date)");

            rs = s.executeQuery("SELECT count(*) FROM pg_constraint WHERE conrelid = 'assistant_user_daily_usage'::regclass "
                    + "AND contype = 'f'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).as("pas de clé étrangère vers users").isZero();
        }
        assertThat(constraintDefinition("pk_assistant_user_daily_usage")).contains("PRIMARY KEY (firebase_uid, usage_date)");
        assertThat(constraintDefinition("chk_assistant_user_daily_usage_questions")).contains("questions >= 0");

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO assistant_user_daily_usage (firebase_uid, usage_date, updated_at) "
                    + "VALUES ('uid-1', DATE '2026-10-16', NOW())");
            ResultSet rs = s.executeQuery("SELECT questions FROM assistant_user_daily_usage WHERE firebase_uid = 'uid-1'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
            assertThatThrownBy(() -> s.execute("INSERT INTO assistant_user_daily_usage (firebase_uid, usage_date, "
                    + "updated_at) VALUES ('uid-1', DATE '2026-10-16', NOW())"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("pk_assistant_user_daily_usage");
            assertThatThrownBy(() -> s.execute("UPDATE assistant_user_daily_usage SET questions = -1"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("chk_assistant_user_daily_usage_questions");
        }
    }
}
