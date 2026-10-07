package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V294 : colonnes de sourdine par participant sur {@code conversations} (FLUTTER-CM),
 * nullables pour que les conversations existantes restent non mises en sourdine.
 */
class V294ConversationNotificationsMuteMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target("294")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"sender_notifications_muted_at", "traveler_notifications_muted_at"})
    void addsANullableTimestampColumn(String column) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT is_nullable, data_type FROM information_schema.columns
                     WHERE table_name = 'conversations' AND column_name = ?
                     """)) {
            statement.setString(1, column);
            ResultSet rs = statement.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            assertThat(rs.getString("data_type")).startsWith("timestamp");
        }
    }
}
