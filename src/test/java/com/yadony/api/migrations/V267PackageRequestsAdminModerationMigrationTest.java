package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V267 : statut REMOVED_BY_ADMIN des demandes d'envoi, colonne status_before_removal, et
 * recopie des signalements de demande dans la boîte générique {@code reports}.
 */
class V267PackageRequestsAdminModerationMigrationTest {

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
        Flyway baseline = flywayUpTo("266");
        baseline.clean();
        baseline.migrate();
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    @Test
    void v267_accepteRemovedByAdminEtRefuseUnStatutInconnu() throws Exception {
        UUID sender = seedUser();
        assertThatThrownBy(() -> seedRequest(sender, "REMOVED_BY_ADMIN"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_pkg_req_status");

        flywayUpTo("267").migrate();

        UUID removed = seedRequest(sender, "REMOVED_BY_ADMIN");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE package_requests SET status_before_removal = 'NEGOTIATING' WHERE id = ?")) {
            ps.setObject(1, removed);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        for (String s : new String[]{"DRAFT", "OPEN", "NEGOTIATING", "ACCEPTED", "EXPIRED", "CANCELLED", "COMPLETED"}) {
            seedRequest(sender, s);
        }
        assertThatThrownBy(() -> seedRequest(sender, "REMOVED"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_pkg_req_status");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE package_requests SET status_before_removal = 'REMOVED_BY_ADMIN' WHERE id = ?")) {
            ps.setObject(1, removed);
            assertThatThrownBy(ps::executeUpdate)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_pkg_req_status_before_removal");
        }
    }

    @Test
    void v267_recopieLesSignalementsDeDemandeSansDoublonEtConvertitLeMotif() throws Exception {
        UUID sender = seedUser();
        UUID request = seedRequest(sender, "OPEN");
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        UUID r3 = UUID.randomUUID();
        UUID r4 = UUID.randomUUID();
        UUID r5 = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 1, 10, 30, 0);
        seedLegacyReport(request, r1, "SCAM", "annonce frauduleuse", createdAt);
        seedLegacyReport(request, r2, "PROHIBITED", null, createdAt);
        seedLegacyReport(request, r3, "INAPPROPRIATE", "", createdAt);
        seedLegacyReport(request, r4, "Motif libre", "détails", createdAt);
        seedLegacyReport(request, r5, "FALSE_INFORMATION", null, createdAt);
        // Déjà présent dans reports (même demande, même signalant) : pas de doublon.
        UUID r6 = UUID.randomUUID();
        seedLegacyReport(request, r6, "OTHER", null, createdAt);
        seedGenericReport(request, r6);

        flywayUpTo("267").migrate();

        assertGeneric(request, r1, "SCAM_ATTEMPT", "annonce frauduleuse");
        assertGeneric(request, r2, "PROHIBITED_ITEM", null);
        assertGeneric(request, r3, "INAPPROPRIATE_CONTENT", "");
        assertGeneric(request, r4, "OTHER", "[Motif d'origine : Motif libre] détails");
        assertGeneric(request, r5, "FALSE_INFORMATION", null);
        assertThat(countGeneric(request, r6)).isEqualTo(1);
        assertThat(countGeneric(request, r1)).isEqualTo(1);

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT status, created_at FROM reports WHERE target_id = ? AND reporter_id = ?")) {
            ps.setObject(1, request);
            ps.setObject(2, r1);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("status")).isEqualTo("OPEN");
            assertThat(rs.getObject("created_at", OffsetDateTime.class).toInstant())
                    .isEqualTo(createdAt.toInstant(ZoneOffset.UTC));
        }
    }

    private static UUID seedUser() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (id, firebase_uid, username) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "uid-" + id);
            ps.setString(3, ("u" + id.toString().replace("-", "")).substring(0, 20));
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedRequest(UUID sender, String status) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date,
                         weight_kg, parcel_size, content_category, transport_mode, status)
                     VALUES (?, ?, 'Paris', 'Dakar', CURRENT_DATE + 10, 5, 'SMALL', 'CLOTHES', 'PLANE', ?)
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, sender);
            ps.setString(3, status);
            ps.executeUpdate();
        }
        return id;
    }

    private static void seedLegacyReport(UUID request, UUID reporter, String reason, String details,
                                         LocalDateTime createdAt) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO package_request_reports (package_request_id, reporter_id, reason, details, created_at)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            ps.setObject(1, request);
            ps.setObject(2, reporter);
            ps.setString(3, reason);
            ps.setString(4, details);
            ps.setTimestamp(5, Timestamp.valueOf(createdAt));
            ps.executeUpdate();
        }
    }

    private static void seedGenericReport(UUID request, UUID reporter) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO reports (id, target_type, target_id, reporter_id, reason)
                     VALUES (?, 'PACKAGE_REQUEST', ?, ?, 'OTHER')
                     """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, request);
            ps.setObject(3, reporter);
            ps.executeUpdate();
        }
    }

    private static void assertGeneric(UUID request, UUID reporter, String reason, String description)
            throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT reason, description, target_type FROM reports
                     WHERE target_id = ? AND reporter_id = ?
                     """)) {
            ps.setObject(1, request);
            ps.setObject(2, reporter);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).as("signalement de %s recopié", reporter).isTrue();
            assertThat(rs.getString("target_type")).isEqualTo("PACKAGE_REQUEST");
            assertThat(rs.getString("reason")).isEqualTo(reason);
            assertThat(rs.getString("description")).isEqualTo(description);
        }
    }

    private static int countGeneric(UUID request, UUID reporter) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM reports WHERE target_id = ? AND reporter_id = ?")) {
            ps.setObject(1, request);
            ps.setObject(2, reporter);
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getInt(1);
        }
    }
}
