package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V293 : rangement et retrait d'une discussion de prix par participant (FLUTTER-EJ),
 * sur les fils de demande ({@code negotiation_threads}) et de trajet ({@code bids}).
 * Toutes les colonnes sont nullables : NULL = visible / non archivé.
 */
class V293NegotiationsUserArchiveHideMigrationTest {

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
                .target("293")
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
    @CsvSource({
            "negotiation_threads, sender_archived_at",
            "negotiation_threads, traveler_archived_at",
            "negotiation_threads, sender_hidden_at",
            "negotiation_threads, traveler_hidden_at",
            "bids, negotiation_sender_archived_at",
            "bids, negotiation_traveler_archived_at",
            "bids, negotiation_sender_hidden_at",
            "bids, negotiation_traveler_hidden_at"
    })
    void addsANullableTimestampColumn(String table, String column) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT is_nullable, data_type FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                     """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            ResultSet rs = statement.executeQuery();
            assertThat(rs.next()).as(table + "." + column).isTrue();
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            assertThat(rs.getString("data_type")).isEqualTo("timestamp with time zone");
        }
    }
}
