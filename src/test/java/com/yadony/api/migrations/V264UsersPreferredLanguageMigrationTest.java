package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V264 : langue préférée de l'utilisateur (i18n anglais). Colonne
 * {@code preferred_language}, {@code DEFAULT 'fr'} pour ne rien changer aux
 * comptes existants, et contrainte {@code chk_users_preferred_language} qui
 * n'accepte que {@code fr}/{@code en}.
 */
class V264UsersPreferredLanguageMigrationTest {

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

    @BeforeEach
    void resetSchema() {
        Flyway baseline = flywayUpTo("263");
        baseline.clean();
        baseline.migrate();
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    @Test
    void v264_ligneExistanteEtNouvelleValentFrParDefaut() throws Exception {
        String existingId = seedMinimalUser("u1");

        flywayUpTo("264").migrate();

        assertThat(preferredLanguageOf(existingId)).isEqualTo("fr");

        String newId = seedMinimalUser("u2");
        assertThat(preferredLanguageOf(newId)).isEqualTo("fr");
    }

    @Test
    void v264_uneMiseAJourVersEnEstAcceptee() throws Exception {
        String id = seedMinimalUser("u3");
        flywayUpTo("264").migrate();

        exec("UPDATE users SET preferred_language = 'en' WHERE id = '" + id + "'");

        assertThat(preferredLanguageOf(id)).isEqualTo("en");
    }

    @Test
    void v264_interditUneLangueHorsCatalogue() throws Exception {
        String id = seedMinimalUser("u4");
        flywayUpTo("264").migrate();

        assertThatThrownBy(() -> exec("UPDATE users SET preferred_language = 'de' WHERE id = '" + id + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_users_preferred_language");
    }

    /**
     * Le minimum de colonnes NOT NULL sans DEFAULT à ce stade du schéma :
     * {@code firebase_uid} (V1) et {@code username} (V183, rendue NOT NULL sans
     * défaut SQL — seul {@code UserEntity.ensureUsername()} l'alimente côté JPA).
     */
    private static String seedMinimalUser(String uid) throws SQLException {
        return insertReturningId(
                "INSERT INTO users (firebase_uid, username) VALUES ('" + uid + "', '" + uid + "-username') RETURNING id");
    }

    private static String insertReturningId(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String preferredLanguageOf(String id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT preferred_language FROM users WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
