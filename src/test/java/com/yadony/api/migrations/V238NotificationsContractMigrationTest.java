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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V238 : le backfill du contrat de forme (catégorie, clé de groupe, deeplink,
 * texte complet des annonces) et la sortie des messages du feed.
 */
class V238NotificationsContractMigrationTest {

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
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void resetSchema() {
        Flyway baseline = flywayUpTo("237");
        baseline.clean();
        baseline.migrate();
    }

    @Test
    void backfillsCategoryGroupKeyAndDeeplinkFromTypeAndData() throws Exception {
        UUID annId = UUID.randomUUID();
        UUID bidId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID offer1, offer2, payment, negotiation, unknown, cancelled;
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            offer1 = seed(connection, userId, "BID_CREATED", "Nouvelle demande d'envoi", "Karim veut envoyer 12 kg",
                    "{\"type\":\"BID_CREATED\",\"bidId\":\"" + bidId + "\",\"announcementId\":\"" + annId + "\"}");
            offer2 = seed(connection, userId, "BID_CREATED", "Nouvelle demande d'envoi", "Amadou veut envoyer 8 kg",
                    "{\"type\":\"BID_CREATED\",\"bidId\":\"" + UUID.randomUUID() + "\",\"announcementId\":\"" + annId + "\"}");
            payment = seed(connection, userId, "PAYMENT_RELEASED", "Paiement reçu !", "45 EUR",
                    "{\"type\":\"PAYMENT_RELEASED\",\"bidId\":\"" + bidId + "\"}");
            negotiation = seed(connection, userId, "negotiation_counter", "Contre-proposition", "40 EUR",
                    "{\"type\":\"negotiation_counter\",\"threadId\":\"" + threadId + "\"}");
            unknown = seed(connection, userId, "TYPE_FUTUR", "Titre", "Corps", "{\"type\":\"TYPE_FUTUR\"}");
            cancelled = seed(connection, userId, "TRIP_CANCELLED", "Trajet annulé", "Remboursement en cours",
                    "{\"type\":\"TRIP_CANCELLED\"}");
        }

        flywayUpTo("238").migrate();

        assertRow(offer1, "COLIS", "bid:announcement:" + annId, "yadony://announcements/" + annId + "/bids", null);
        assertRow(offer2, "COLIS", "bid:announcement:" + annId, "yadony://announcements/" + annId + "/bids", null);
        assertRow(payment, "PAIEMENTS", null, "yadony://bids/" + bidId, null);
        assertRow(negotiation, "TRAJETS", "request:thread:" + threadId, "yadony://negotiations/" + threadId, null);
        assertRow(unknown, "COLIS", null, null, null);
        assertRow(cancelled, "COLIS", null, "yadony://profile/shipments/history", null);
    }

    @Test
    void announcementsKeepTheirFullTextAndMessagesLeaveTheFeed() throws Exception {
        UUID broadcast, message;
        String text = "Conditions de transport mises à jour : à compter du 15 septembre, les colis de plus de 20 kg "
                + "devront être accompagnés d'une facture d'achat.";
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            broadcast = seed(connection, userId, "ADMIN_BROADCAST", "Conditions", text,
                    "{\"type\":\"ADMIN_BROADCAST\",\"broadcastId\":\"" + UUID.randomUUID() + "\"}");
            message = seed(connection, userId, "NEW_MESSAGE", "Message de Fatou", "Bonjour",
                    "{\"type\":\"NEW_MESSAGE\",\"conversationId\":\"" + UUID.randomUUID() + "\"}");
        }

        flywayUpTo("238").migrate();

        assertRow(broadcast, "ANNONCE", null, null, text);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT deleted_at FROM notifications WHERE id = ?")) {
            statement.setObject(1, message);
            ResultSet rs = statement.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp("deleted_at")).as("NEW_MESSAGE soft-deleted").isNotNull();
        }
    }

    @Test
    void everyExistingRowHasACategoryAfterMigration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            for (String type : new String[]{"BID_ACCEPTED", "KYC_VERIFIED", "CORRIDOR_ALERT", "SYSTEM", "PROMO", ""}) {
                seed(connection, userId, type, "t", "b", "{}");
            }
        }

        flywayUpTo("238").migrate();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            ResultSet rs = statement.executeQuery("SELECT count(*) FROM notifications WHERE category IS NULL");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    private void assertRow(UUID id, String category, String groupKey, String deeplink, String fullBody) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT category, group_key, deeplink, full_body FROM notifications WHERE id = ?")) {
            statement.setObject(1, id);
            ResultSet rs = statement.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("category")).as("category").isEqualTo(category);
            assertThat(rs.getString("group_key")).as("group_key").isEqualTo(groupKey);
            assertThat(rs.getString("deeplink")).as("deeplink").isEqualTo(deeplink);
            assertThat(rs.getString("full_body")).as("full_body").isEqualTo(fullBody);
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

    private UUID seed(Connection connection, UUID userId, String type, String title, String body, String json)
            throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO notifications (id, user_id, type, title, body, data, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, now(), now())
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, userId);
            statement.setString(3, type);
            statement.setString(4, title);
            statement.setString(5, body);
            statement.setString(6, json);
            statement.executeUpdate();
        }
        return id;
    }
}
