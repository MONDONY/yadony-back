package com.yadony.api.auth;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Migration du changement d'identifiant sur un vrai PostgreSQL (la suite H2 désactive Flyway) :
 * Flyway joue toutes les migrations sur base vide, puis on vérifie l'index d'unicité insensible
 * à la casse, la colonne de dernier changement et la table de réservation.
 */
@DisplayName("Changement d'identifiant - migration")
class UsernameMigrationIT {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        exec("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").cleanDisabled(false).load().migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    @DisplayName("la migration s'applique sur base vide : colonne et table présentes")
    void migrationAppliesOnEmptyDatabase() throws Exception {
        assertThat(scalar("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'users' AND column_name = 'username_changed_at'"))
                .isEqualTo("timestamp with time zone");
        assertThat(scalar("SELECT count(*)::text FROM information_schema.columns "
                + "WHERE table_name = 'released_usernames'")).isEqualTo("3");
        assertThat(scalar("SELECT indexname FROM pg_indexes WHERE indexname = 'ux_users_username_lower'"))
                .isEqualTo("ux_users_username_lower");
    }

    @Test
    @DisplayName("« Awa » et « awa » violent ux_users_username_lower")
    void caseDuplicatesAreRejected() throws Exception {
        insertUser("Awa", false);
        assertThatThrownBy(() -> insertUser("awa", false))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ux_users_username_lower");
    }

    @Test
    @DisplayName("l'index couvre aussi les comptes supprimés (le nom reste réservé)")
    void softDeletedRowsStillHoldTheName() throws Exception {
        insertUser("Bintou", true);
        assertThatThrownBy(() -> insertUser("bintou", false))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("ux_users_username_lower");
    }

    private static void insertUser(String username, boolean deleted) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO users (id, firebase_uid, username, deleted_at) VALUES ('" + id + "', 'mig-" + id
                + "', '" + username + "', " + (deleted ? "now()" : "NULL") + ")");
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
