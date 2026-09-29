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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V274 : un signalement de l'app garde le lien vers la conversation support ouverte
 * pour répondre au signalant. Colonne nullable (les signalements existants n'en ont
 * pas), clé étrangère vers support_tickets, index pour le lien inverse.
 */
class V274ReportsSupportTicketMigrationTest {

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
    void v274_ajouteLeLienNullableVersLeTicketSupport() throws Exception {
        Flyway baseline = flywayUpTo("273");
        baseline.clean();
        baseline.migrate();
        UUID existing = seedReport();

        flywayUpTo("274").migrate();

        // Les signalements existants restent sans lien.
        assertThat(ticketOf(existing)).isNull();

        UUID ticket = seedTicket(seedUser());
        link(existing, ticket);
        assertThat(ticketOf(existing)).isEqualTo(ticket);

        // Clé étrangère : un ticket inexistant est refusé.
        assertThatThrownBy(() -> link(existing, UUID.randomUUID()))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_reports_support_ticket");

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT indexdef FROM pg_indexes WHERE tablename = 'reports' AND indexname = ?")) {
            ps.setString(1, "idx_reports_support_ticket");
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("support_ticket_id");
        }
    }

    private static UUID seedReport() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO reports (id, target_type, reason, screen_route) VALUES (?, 'APP', 'SCREEN_BUG', '/home')")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedUser() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                    VALUES ('%s', 'uid-%s', 'user-%s', 'ACTIVE', now(), now())
                    """.formatted(id, id, id.toString().substring(0, 12)));
        }
        return id;
    }

    private static UUID seedTicket(UUID userId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO support_tickets
                    (id, user_id, category, subject, last_message_at, created_at, updated_at)
                VALUES (?, ?, 'OTHER', 'Votre signalement', now(), now(), now())
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.executeUpdate();
        }
        return id;
    }

    private static void link(UUID reportId, UUID ticketId) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE reports SET support_ticket_id = ? WHERE id = ?")) {
            ps.setObject(1, ticketId);
            ps.setObject(2, reportId);
            ps.executeUpdate();
        }
    }

    private static UUID ticketOf(UUID reportId) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT support_ticket_id FROM reports WHERE id = ?")) {
            ps.setObject(1, reportId);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            return rs.getObject(1, UUID.class);
        }
    }
}
