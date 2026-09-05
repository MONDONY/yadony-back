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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V241 : verrou base contre le double deposit/payout/refund — au plus une opération
 * vivante ou aboutie par {@code (payment_id, kind)}, via l'index unique partiel
 * {@code uq_pawapay_ops_live_per_payment}. H2 (profil test des autres suites, Flyway
 * désactivé, schéma dérivé des entités JPA) ne porte pas cet index : ce test est la
 * seule preuve, sous un vrai PostgreSQL, qu'il existe réellement et qu'il rejette bien
 * la seconde ligne concurrente — pas seulement que le catch applicatif sait traduire
 * une {@code DataIntegrityViolationException} (ça, {@code PawapayOperationServiceTest}
 * le prouve déjà, mais sur un dépôt mocké qui la lève artificiellement).
 */
class V241PawapayOperationsMigrationTest {

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
        Flyway flyway = flywayLatest();
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void secondLiveOperation_forSamePaymentAndKind_isRejectedByPartialUniqueIndex() throws Exception {
        UUID paymentId;
        try (Connection connection = dataSource.getConnection()) {
            paymentId = seedPayment(connection);
            insertOperation(connection, UUID.randomUUID(), paymentId, "DEPOSIT", "CREATED");
        }

        UUID finalPaymentId = paymentId;
        assertThatThrownBy(() -> {
            try (Connection connection = dataSource.getConnection()) {
                insertOperation(connection, UUID.randomUUID(), finalPaymentId, "DEPOSIT", "ACCEPTED");
            }
        }).isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_pawapay_ops_live_per_payment");
    }

    @Test
    void secondOperation_forDifferentKind_isNotBlockedByTheSamePaymentIndex() throws Exception {
        // Contrôle négatif : l'index est sur (payment_id, kind) — un DEPOSIT vivant ne
        // doit jamais bloquer un REFUND ou un PAYOUT pour le même paiement.
        UUID paymentId;
        try (Connection connection = dataSource.getConnection()) {
            paymentId = seedPayment(connection);
            insertOperation(connection, UUID.randomUUID(), paymentId, "DEPOSIT", "COMPLETED");
        }

        try (Connection connection = dataSource.getConnection()) {
            insertOperation(connection, UUID.randomUUID(), paymentId, "REFUND", "CREATED");
        }
        // pas d'exception : kind différent, l'index unique ne s'applique pas.
    }

    @Test
    void insertionBecomesPossibleAgain_oncePreviousOperationIsFailed() throws Exception {
        UUID paymentId;
        UUID firstId = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection()) {
            paymentId = seedPayment(connection);
            insertOperation(connection, firstId, paymentId, "DEPOSIT", "CREATED");
        }

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE pawapay_operations SET status = 'FAILED' WHERE id = ?")) {
            statement.setObject(1, firstId);
            statement.executeUpdate();
        }

        try (Connection connection = dataSource.getConnection()) {
            insertOperation(connection, UUID.randomUUID(), paymentId, "DEPOSIT", "CREATED");
        }
        // pas d'exception : l'opération FAILED n'est plus "vivante", une relance reste possible.

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            var rs = statement.executeQuery(
                    "SELECT count(*) FROM pawapay_operations WHERE payment_id = '" + paymentId + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getLong(1)).as("les 2 lignes coexistent (une FAILED, une CREATED)").isEqualTo(2);
        }
    }

    private Flyway flywayLatest() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .cleanDisabled(false)
                .load();
    }

    // Chaîne minimale pour respecter fk_payments_bid : user (voyageur) → announcement →
    // bid (expéditeur) → payment. Colonnes reprises telles quelles de
    // V216MigrationTest (déjà éprouvées contre le vrai schéma Postgres).

    private UUID seedUser(Connection connection) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'ACTIVE', now(), now())
                    """.formatted(id, "uid-" + id.toString().substring(0, 8), "user-" + id.toString().substring(0, 8)));
        }
        return id;
    }

    private UUID seedAnnouncement(Connection connection, UUID travelerId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                      available_kg, price_per_kg, transport_mode, total_kg,
                      pickup_address_label, pickup_lat, pickup_lng,
                      delivery_address_label, delivery_lat, delivery_lng,
                      created_at, updated_at)
                    VALUES ('%s', '%s', 'Paris', 'Dakar', CURRENT_DATE + 10,
                      3.00, 15.00, 'PLANE', 3.00,
                      '12 rue de Paris', 48.8566, 2.3522,
                      'Plateau, Dakar', 14.6928, -17.4467,
                      now(), now())
                    """.formatted(id, travelerId));
        }
        return id;
    }

    private UUID seedBid(Connection connection, UUID announcementId, UUID senderId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description,
                      recipient_name, recipient_phone, status, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 3.00, 'Vetements',
                      'Aminata', '+221701234567', 'PENDING', now(), now())
                    """.formatted(id, announcementId, senderId));
        }
        return id;
    }

    private UUID seedPayment(Connection connection) throws SQLException {
        UUID travelerId = seedUser(connection);
        UUID senderId = seedUser(connection);
        UUID announcementId = seedAnnouncement(connection, travelerId);
        UUID bidId = seedBid(connection, announcementId, senderId);

        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO payments (id, bid_id, stripe_payment_intent_id, amount, currency, "
                        + "commission_amount, status, legacy_destination_charge, disputed, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 150.00, 'XOF', 18.00, 'PENDING', false, false, NOW(), NOW())")) {
            statement.setObject(1, id);
            statement.setObject(2, bidId);
            statement.setString(3, "pi_" + id);
            statement.executeUpdate();
        }
        return id;
    }

    private void insertOperation(Connection connection, UUID id, UUID paymentId, String kind, String status)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO pawapay_operations (id, kind, status, amount, currency, provider, country, "
                        + "msisdn, msisdn_masked, payment_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 15000, 'XOF', 'ORANGE_SEN', 'SN', 'enc', '+221 •••• 67', ?, NOW(), NOW())")) {
            statement.setObject(1, id);
            statement.setString(2, kind);
            statement.setString(3, status);
            statement.setObject(4, paymentId);
            statement.executeUpdate();
        }
    }
}
