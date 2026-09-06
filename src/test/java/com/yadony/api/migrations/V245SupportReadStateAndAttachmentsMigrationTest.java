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
 * V245 : etat de lecture cote utilisateur et pieces jointes des messages.
 */
class V245SupportReadStateAndAttachmentsMigrationTest {

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
        Flyway baseline = flywayUpTo("244");
        baseline.clean();
        baseline.migrate();
    }

    @Test
    void addsTheUserReadColumnNullableByDefault() throws Exception {
        flywayUpTo("245").migrate();

        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            UUID ticketId = seedTicket(connection, userId);

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT user_last_read_at FROM support_tickets WHERE id = ?")) {
                statement.setObject(1, ticketId);
                ResultSet rs = statement.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getObject("user_last_read_at")).isNull();
            }
        }
    }

    @Test
    void storesAnAttachmentAttachedToAMessage() throws Exception {
        flywayUpTo("245").migrate();

        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            UUID ticketId = seedTicket(connection, userId);
            UUID messageId = seedMessage(connection, ticketId, userId);
            seedAttachment(connection, messageId, "support/" + userId + "/1_a.jpg");

            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT object_key, content_type, size_bytes
                    FROM support_message_attachments WHERE message_id = ?
                    """)) {
                statement.setObject(1, messageId);
                ResultSet rs = statement.executeQuery();
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("object_key")).isEqualTo("support/" + userId + "/1_a.jpg");
                assertThat(rs.getString("content_type")).isEqualTo("image/jpeg");
                assertThat(rs.getLong("size_bytes")).isEqualTo(1024L);
            }
        }
    }

    /** Une piece jointe orpheline n'a aucun sens : la cle etrangere doit tenir. */
    @Test
    void rejectsAnAttachmentWithoutAnExistingMessage() throws Exception {
        flywayUpTo("245").migrate();

        try (Connection connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> seedAttachment(connection, UUID.randomUUID(), "support/x/1_a.jpg"))
                    .isInstanceOf(SQLException.class);
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

    private UUID seedMessage(Connection connection, UUID ticketId, UUID authorId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO support_messages
                    (id, ticket_id, author_type, author_id, content, created_at, updated_at)
                VALUES (?, ?, 'USER', ?, 'Bonjour', now(), now())
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, ticketId);
            statement.setObject(3, authorId);
            statement.executeUpdate();
        }
        return id;
    }

    private void seedAttachment(Connection connection, UUID messageId, String objectKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO support_message_attachments
                    (id, message_id, object_key, content_type, size_bytes, created_at, updated_at)
                VALUES (?, ?, ?, 'image/jpeg', 1024, now(), now())
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, messageId);
            statement.setString(3, objectKey);
            statement.executeUpdate();
        }
    }
}
