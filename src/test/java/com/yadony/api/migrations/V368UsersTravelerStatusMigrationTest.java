package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V368 : statut légal du voyageur (particulier ou professionnel) sur {@code users}. Les comptes
 * existants restent non déclarés (NULL) ; un professionnel porte raison sociale, numéro et pays,
 * un particulier n'en porte aucun.
 */
class V368UsersTravelerStatusMigrationTest {

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
    void v368_statutNullPourLesComptesExistants_etContraintesDeCoherence() throws Exception {
        Flyway baseline = flywayUpTo("367");
        baseline.clean();
        baseline.migrate();
        UUID existing = seedUser();

        flywayUpTo("368").migrate();

        // Les comptes existants ne sont pas déclarés.
        assertThat(statusOf(existing)).isNull();

        // Valeur hors liste refusée.
        assertThatThrownBy(() -> update(existing, "ROBOT", null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_users_traveler_status");

        // PROFESSIONAL sans raison sociale, sans numéro ou sans pays : refusé.
        assertThatThrownBy(() -> update(existing, "PROFESSIONAL", null, "SIREN-123", "FR"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_users_traveler_status_pro_fields");
        assertThatThrownBy(() -> update(existing, "PROFESSIONAL", "Acme", null, "FR"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_users_traveler_status_pro_fields");
        assertThatThrownBy(() -> update(existing, "PROFESSIONAL", "Acme", "SIREN-123", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_users_traveler_status_pro_fields");

        // INDIVIDUAL avec un champ professionnel renseigné : refusé.
        assertThatThrownBy(() -> update(existing, "INDIVIDUAL", "Acme", null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_users_traveler_status_pro_fields");

        // Statut NULL avec un champ professionnel : refusé aussi.
        assertThatThrownBy(() -> update(existing, null, null, null, "FR"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_users_traveler_status_pro_fields");

        // Cas valides.
        assertThatCode(() -> update(existing, "PROFESSIONAL", "Acme", "SIREN-123", "FR"))
                .doesNotThrowAnyException();
        assertThat(statusOf(existing)).isEqualTo("PROFESSIONAL");
        assertThatCode(() -> update(existing, "INDIVIDUAL", null, null, null)).doesNotThrowAnyException();
        assertThat(statusOf(existing)).isEqualTo("INDIVIDUAL");
        assertThatCode(() -> update(existing, null, null, null, null)).doesNotThrowAnyException();
        assertThat(statusOf(existing)).isNull();
    }

    private static UUID seedUser() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("INSERT INTO users (firebase_uid, username) VALUES ('v368-" + suffix
                     + "', 'v368_" + suffix + "') RETURNING id")) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }

    private static void update(UUID id, String status, String name, String number, String country)
            throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET traveler_status = ?, business_name = ?, business_registration_number = ?, "
                        + "business_country = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setString(2, name);
            ps.setString(3, number);
            ps.setString(4, country);
            ps.setObject(5, id);
            ps.executeUpdate();
        }
    }

    private static String statusOf(UUID id) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT traveler_status FROM users WHERE id = ?")) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
