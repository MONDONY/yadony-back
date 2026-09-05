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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V244 : les trois tables de la messagerie support et le catalogue initial de
 * reponses predefinies.
 */
class V244SupportMessagingMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void resetSchema() {
        Flyway baseline = flywayUpTo("240");
        baseline.clean();
        baseline.migrate();
    }

    @Test
    void createsTheThreeSupportTables() throws Exception {
        flywayUpTo("244").migrate();

        assertThat(tableExists("support_tickets")).isTrue();
        assertThat(tableExists("support_messages")).isTrue();
        assertThat(tableExists("support_predefined_replies")).isTrue();
    }

    @Test
    void seedsAnActivePredefinedReplyCatalogue() throws Exception {
        flywayUpTo("244").migrate();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            ResultSet rs = statement.executeQuery(
                    "SELECT count(*) FROM support_predefined_replies WHERE active IS TRUE");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isGreaterThanOrEqualTo(8);

            ResultSet ordered = statement.executeQuery(
                    "SELECT code FROM support_predefined_replies ORDER BY sort_order ASC LIMIT 1");
            assertThat(ordered.next()).isTrue();
            assertThat(ordered.getString("code")).isEqualTo("account-verification");
        }
    }

    @Test
    void storesATicketAndItsFirstMessage() throws Exception {
        flywayUpTo("244").migrate();

        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            UUID ticketId = seedTicket(connection, userId);
            seedMessage(connection, ticketId, "USER", userId, "Je ne vois pas le remboursement.");

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT t.status, t.priority, t.assigned_admin_id, m.content
                    FROM support_tickets t
                    JOIN support_messages m ON m.ticket_id = t.id
                    WHERE t.id = ?
                    """)) {
                statement.setObject(1, ticketId);
                ResultSet rs = statement.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("NEW");
                assertThat(rs.getString("priority")).isEqualTo("NORMAL");
                assertThat(rs.getObject("assigned_admin_id")).isNull();
                assertThat(rs.getString("content")).isEqualTo("Je ne vois pas le remboursement.");
            }
        }
    }

    /** Un ticket orphelin n'a aucun sens : la cle etrangere vers users doit tenir. */
    @Test
    void rejectsATicketWithoutAnExistingUser() throws Exception {
        flywayUpTo("244").migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> seedTicket(connection, UUID.randomUUID()))
                    .isInstanceOf(SQLException.class);
        }
    }

    private boolean tableExists(String table) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT to_regclass('public.' || ?::text) IS NOT NULL")) {
            statement.setString(1, table);
            ResultSet rs = statement.executeQuery();
            return rs.next() && rs.getBoolean(1);
        }
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target(targetVersion)
                .cleanDisabled(false)
                .load();
    }

    private UUID seedUser(Connection connection) throws Exception {
        UUID id = UUID.randomUUID();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                    VALUES ('%s', 'uid-%s', 'user-%s', 'ACTIVE', now(), now())
                    """.formatted(id, id, id.toString().substring(0, 12)));
        }
        return id;
    }

    private UUID seedTicket(Connection connection, UUID userId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO support_tickets
                    (id, user_id, category, subject, last_message_at, created_at, updated_at)
                VALUES (?, ?, 'PAYMENT', 'Paiement bloque', now(), now(), now())
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, userId);
            statement.executeUpdate();
        }
        return id;
    }

    private void seedMessage(Connection connection, UUID ticketId, String authorType, UUID authorId, String content)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO support_messages
                    (id, ticket_id, author_type, author_id, content, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, ticketId);
            statement.setString(3, authorType);
            statement.setObject(4, authorId);
            statement.setString(5, content);
            statement.executeUpdate();
        }
    }
}
