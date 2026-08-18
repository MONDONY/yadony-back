package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V215/V216 — colonnes et tables de la négociation de prix d'un trajet.
 *
 * <p>Comme V210/V211/V212, ce test tourne sur un PostgreSQL embarqué avec le vrai
 * Flyway : le profil de test désactive Flyway et génère le schéma H2 depuis les
 * entités JPA, donc la SQL des migrations n'est vérifiée par rien d'autre. Un
 * {@code ADD COLUMN NOT NULL} sur une table déjà peuplée est précisément le genre
 * de migration qui passe en développement (table vide) et échoue en production :
 * les tests ci-dessous insèrent donc une ligne AVANT de migrer.
 */
class V216MigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDb() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDb() throws Exception {
        if (postgres != null) postgres.close();
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .cleanDisabled(false)
                .target(targetVersion)
                .load();
    }

    private void resetAndMigrateTo(String target) {
        Flyway reset = flywayUpTo("214");
        reset.clean();
        reset.migrate();
        flywayUpTo(target).migrate();
    }

    private boolean columnExists(Connection c, String table, String column) throws SQLException {
        try (Statement st = c.createStatement()) {
            var rs = st.executeQuery(
                    "SELECT count(*) FROM information_schema.columns"
                            + " WHERE table_name = '" + table + "' AND column_name = '" + column + "'");
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private boolean tableExists(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement()) {
            var rs = st.executeQuery(
                    "SELECT count(*) FROM information_schema.tables WHERE table_name = '" + table + "'");
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private UUID seedUser(Connection c) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
                INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'ACTIVE', now(), now())
                """.formatted(id, "uid-" + id.toString().substring(0, 8),
                    "user-" + id.toString().substring(0, 8)));
        }
        return id;
    }

    private UUID seedAnnouncement(Connection c, UUID travelerId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
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

    private UUID seedBid(Connection c, UUID announcementId, UUID senderId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
                INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description,
                  recipient_name, recipient_phone, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 3.00, 'Vetements',
                  'Aminata', '+221701234567', 'PENDING', now(), now())
                """.formatted(id, announcementId, senderId));
        }
        return id;
    }

    @Test
    @DisplayName("V215 : announcements.negotiable existe et vaut false pour un trajet préexistant")
    void announcementsNegotiableDefaultsToFalseOnExistingRows() throws Exception {
        // La ligne est insérée AVANT V215 : c'est le seul moyen de vérifier que le
        // DEFAULT FALSE couvre bien les trajets déjà publiés (un trajet historique
        // reste à prix ferme, il n'ouvre pas la négociation sans que son voyageur l'ait voulu).
        resetAndMigrateTo("214");

        UUID announcementId;
        try (Connection c = dataSource.getConnection()) {
            UUID travelerId = seedUser(c);
            announcementId = seedAnnouncement(c, travelerId);
        }

        flywayUpTo("215").migrate();

        try (Connection c = dataSource.getConnection()) {
            assertThat(columnExists(c, "announcements", "negotiable")).isTrue();
            try (Statement st = c.createStatement()) {
                var rs = st.executeQuery(
                        "SELECT negotiable FROM announcements WHERE id = '" + announcementId + "'");
                rs.next();
                assertThat(rs.getBoolean(1)).isFalse();
            }
        }
    }

    @Test
    @DisplayName("V216 : les colonnes de négociation sont présentes sur bids")
    void bidNegotiationColumnsExist() throws Exception {
        resetAndMigrateTo("216");

        try (Connection c = dataSource.getConnection()) {
            assertThat(columnExists(c, "bids", "negotiated_gross_eur")).isTrue();
            assertThat(columnExists(c, "bids", "negotiation_round")).isTrue();
            assertThat(columnExists(c, "bids", "sender_last_read_at")).isTrue();
            assertThat(columnExists(c, "bids", "traveler_last_read_at")).isTrue();
        }
    }

    @Test
    @DisplayName("V216 : un bid préexistant reçoit negotiation_round = 0 sans violer le NOT NULL")
    void existingBidGetsZeroRound() throws Exception {
        resetAndMigrateTo("214");

        UUID bidId;
        try (Connection c = dataSource.getConnection()) {
            UUID travelerId = seedUser(c);
            UUID senderId = seedUser(c);
            UUID announcementId = seedAnnouncement(c, travelerId);
            bidId = seedBid(c, announcementId, senderId);
        }

        flywayUpTo("216").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery(
                    "SELECT negotiation_round, negotiated_gross_eur FROM bids WHERE id = '" + bidId + "'");
            rs.next();
            assertThat(rs.getInt(1)).isZero();
            assertThat(rs.getBigDecimal(2)).isNull();
        }
    }

    @Test
    @DisplayName("V216 : les deux tables du fil existent")
    void negotiationTablesExist() throws Exception {
        resetAndMigrateTo("216");

        try (Connection c = dataSource.getConnection()) {
            assertThat(tableExists(c, "bid_negotiation_messages")).isTrue();
            assertThat(tableExists(c, "bid_custom_items")).isTrue();
        }
    }

    @Test
    @DisplayName("V216 : un message et une ligne hors grille s'insèrent avec leurs FK")
    void negotiationRowsCanBeInserted() throws Exception {
        resetAndMigrateTo("216");

        try (Connection c = dataSource.getConnection()) {
            UUID travelerId = seedUser(c);
            UUID senderId = seedUser(c);
            UUID announcementId = seedAnnouncement(c, travelerId);
            UUID bidId = seedBid(c, announcementId, senderId);

            try (Statement st = c.createStatement()) {
                st.executeUpdate("""
                    INSERT INTO bid_negotiation_messages
                      (id, bid_id, author_id, kind, proposed_gross_eur, body, created_at)
                    VALUES ('%s', '%s', '%s', 'PROPOSAL', 45.00, 'Je propose 45 euros', now())
                    """.formatted(UUID.randomUUID(), bidId, senderId));
                st.executeUpdate("""
                    INSERT INTO bid_custom_items (id, bid_id, label, quantity, amount_eur, created_at)
                    VALUES ('%s', '%s', 'Tapis', 2, 20.00, now())
                    """.formatted(UUID.randomUUID(), bidId));

                var rs = st.executeQuery(
                        "SELECT (SELECT count(*) FROM bid_negotiation_messages WHERE bid_id = '" + bidId + "'),"
                                + " (SELECT count(*) FROM bid_custom_items WHERE bid_id = '" + bidId + "')");
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.getInt(2)).isEqualTo(1);
            }
        }
    }
}
