package com.yadony.api.matching;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Migration du compte destinataire désigné sur le colis, sur un vrai PostgreSQL (la suite H2 désactive
 * Flyway) : Flyway joue toutes les migrations sur base vide, puis on vérifie la colonne et son index.
 */
@DisplayName("Compte destinataire du colis - migration")
class BidRecipientUserMigrationIT {

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
    @DisplayName("la migration s'applique sur base vide : colonne nullable et index présents")
    void migrationAppliesOnEmptyDatabase() throws Exception {
        assertThat(scalar("SELECT data_type || '/' || is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'bids' AND column_name = 'recipient_user_id'"))
                .isEqualTo("uuid/YES");
        assertThat(scalar("SELECT indexname FROM pg_indexes WHERE indexname = 'ix_bids_recipient_user_id'"))
                .isEqualTo("ix_bids_recipient_user_id");
    }

    @Test
    @DisplayName("la colonne accepte NULL et un UUID")
    void columnAcceptsNullAndUuid() throws Exception {
        // Copie sans contraintes : un colis complet exige de nombreuses colonnes NOT NULL sans rapport ici.
        // Table temporaire = propre à la connexion : tout passe par la même.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TEMP TABLE bids_probe AS SELECT * FROM bids WITH NO DATA");
            s.execute("INSERT INTO bids_probe (recipient_user_id) VALUES (NULL)");
            s.execute("INSERT INTO bids_probe (recipient_user_id) VALUES ('0b2f1c6e-7d3a-4c1e-9a55-3f0f6d2d9a11')");
            assertThat(one(s, "SELECT count(*)::text FROM bids_probe WHERE recipient_user_id IS NULL")).isEqualTo("1");
            assertThat(one(s, "SELECT recipient_user_id::text FROM bids_probe WHERE recipient_user_id IS NOT NULL"))
                    .isEqualTo("0b2f1c6e-7d3a-4c1e-9a55-3f0f6d2d9a11");
        }
    }

    private static String one(Statement s, String sql) throws SQLException {
        try (ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
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
