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
 * V365 : origine d'un signalement (USER par défaut, SYSTEM pour la détection automatique) et
 * un seul signalement système ouvert par cible.
 */
class V365ReportsSourceMigrationTest {

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
    void v365_sourceParDefautUtilisateur_etUnSeulSignalementSystemeOuvertParCible() throws Exception {
        Flyway baseline = flywayUpTo("364");
        baseline.clean();
        baseline.migrate();
        UUID existing = insertReport(null, "OPEN", null);

        flywayUpTo("365").migrate();

        // Les signalements existants deviennent des signalements d'utilisateur.
        assertThat(sourceOf(existing)).isEqualTo("USER");

        // Valeur hors liste refusée.
        assertThatThrownBy(() -> insertReport(UUID.randomUUID(), "OPEN", "ROBOT"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_reports_source");

        // Un seul signalement SYSTEM ouvert par cible…
        UUID bid = UUID.randomUUID();
        UUID first = insertReport(bid, "OPEN", "SYSTEM");
        assertThatThrownBy(() -> insertReport(bid, "OPEN", "SYSTEM"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_reports_system_open_target");

        // … mais un utilisateur peut toujours signaler la même cible,
        assertThatCode(() -> insertReport(bid, "OPEN", "USER")).doesNotThrowAnyException();

        // et une fois le premier traité, une nouvelle détection est possible.
        resolve(first);
        assertThatCode(() -> insertReport(bid, "OPEN", "SYSTEM")).doesNotThrowAnyException();
    }

    private static UUID insertReport(UUID targetId, String status, String source) throws SQLException {
        UUID id = UUID.randomUUID();
        String sql = source == null
                ? "INSERT INTO reports (id, target_type, target_id, reason, status) VALUES (?, 'BID', ?, 'PROHIBITED_ITEM', ?)"
                : "INSERT INTO reports (id, target_type, target_id, reason, status, source) VALUES (?, 'BID', ?, 'PROHIBITED_ITEM', ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setObject(2, targetId != null ? targetId : UUID.randomUUID());
            ps.setString(3, status);
            if (source != null) ps.setString(4, source);
            ps.executeUpdate();
        }
        return id;
    }

    private static void resolve(UUID reportId) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE reports SET status = 'RESOLVED' WHERE id = ?")) {
            ps.setObject(1, reportId);
            ps.executeUpdate();
        }
    }

    private static String sourceOf(UUID reportId) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT source FROM reports WHERE id = ?")) {
            ps.setObject(1, reportId);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
