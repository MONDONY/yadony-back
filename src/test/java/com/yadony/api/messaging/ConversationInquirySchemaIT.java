package com.yadony.api.messaging;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V383 (conversations INQUIRY) sur un vrai PostgreSQL : unicité partielle et CHECK. */
@DisplayName("Conversations INQUIRY - schéma V383")
class ConversationInquirySchemaIT {

    private static EmbeddedPostgres postgres;
    private static DataSource ds;
    private static UUID asker;
    private static UUID owner;
    private static UUID announcement;
    private static UUID legacyConversation;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        ds = postgres.getPostgresDatabase();
        exec("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        flyway("382").migrate();
        asker = seedUser();
        owner = seedUser();
        announcement = seedAnnouncement();
        UUID bid = seedBid();
        legacyConversation = insertConversation("SENDER_TRAVELER", bid, null, null);
        flyway("latest").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    @Test
    @DisplayName("une conversation existante survit à la migration, colonnes neuves nulles")
    void legacyRowIntact() throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT kind, announcement_id, asker_id, quota_lifted_at FROM conversations WHERE id = ?")) {
            ps.setObject(1, legacyConversation);
            var rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("SENDER_TRAVELER");
            assertThat(rs.getObject(2)).isNull();
            assertThat(rs.getObject(3)).isNull();
            assertThat(rs.getObject(4)).isNull();
        }
    }

    @Test
    @DisplayName("deux INQUIRY ouvertes du même couple violent l'unicité, une fermée ne compte pas")
    void duplicateInquiry_isRejected() throws Exception {
        UUID otherAnnouncement = seedAnnouncement();
        insertConversation("INQUIRY", null, asker, otherAnnouncement);
        assertThatThrownBy(() -> insertConversation("INQUIRY", null, asker, otherAnnouncement))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("conversations_inquiry_active_idx");

        exec("UPDATE conversations SET closed_at = now() WHERE asker_id = '" + asker
                + "' AND announcement_id = '" + otherAnnouncement + "'");
        assertThat(insertConversation("INQUIRY", null, asker, otherAnnouncement)).isNotNull();
    }

    @Test
    @DisplayName("une INQUIRY sans announcement_id ou sans asker_id viole le CHECK")
    void inquiryWithoutRefs_isRejected() {
        assertThatThrownBy(() -> insertConversation("INQUIRY", null, asker, null))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_conversations_inquiry_refs");
        assertThatThrownBy(() -> insertConversation("INQUIRY", null, null, announcement))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_conversations_inquiry_refs");
    }

    @Test
    @DisplayName("tant qu'une conversation promue existe pour le couple, aucune INQUIRY ne peut naître ; promote puis relock réussit")
    void promotedThreadBlocksNewInquiry_andRelockWorks() throws Exception {
        UUID ann = seedAnnouncement();
        UUID inquiry = insertConversation("INQUIRY", null, asker, ann);
        exec("UPDATE conversations SET kind = 'SENDER_TRAVELER', bid_id = '" + seedBid()
                + "' WHERE id = '" + inquiry + "'");
        assertThatThrownBy(() -> insertConversation("INQUIRY", null, asker, ann))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("conversations_inquiry_active_idx");
        exec("UPDATE conversations SET kind = 'INQUIRY', bid_id = NULL WHERE id = '" + inquiry + "'");
    }

    @Test
    @DisplayName("hors INQUIRY, bid_id reste obligatoire")
    void bidRequiredUnlessInquiry() {
        assertThatThrownBy(() -> insertConversation("SENDER_TRAVELER", null, null, null))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_conversations_bid_unless_inquiry");
    }

    @Test
    @DisplayName("un type inconnu reste refusé")
    void unknownKind_isRejected() {
        assertThatThrownBy(() -> insertConversation("FOO", seedBid(), asker, announcement))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_conversations_kind");
    }

    @Test
    @DisplayName("SENDER_TRAVELER avec bid reste insérable")
    void senderTravelerWithBid_stillWorks() throws Exception {
        assertThat(insertConversation("SENDER_TRAVELER", seedBid(), null, null)).isNotNull();
    }

    private static UUID insertConversation(String kind, UUID bid, UUID askerId, UUID announcementId)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO conversations (id, bid_id, sender_id, traveler_id, firestore_conversation_id, kind
                  %s) VALUES (?, ?, ?, ?, ?, ? %s)
                """.formatted(askerId != null || announcementId != null || bid == null ? ", asker_id, announcement_id" : "",
                askerId != null || announcementId != null || bid == null ? ", ?, ?" : ""))) {
            ps.setObject(1, id);
            ps.setObject(2, bid);
            ps.setObject(3, asker);
            ps.setObject(4, owner);
            ps.setString(5, "conv_" + id);
            ps.setString(6, kind);
            if (askerId != null || announcementId != null || bid == null) {
                ps.setObject(7, askerId);
                ps.setObject(8, announcementId);
            }
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedBid() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description,
                  recipient_name, recipient_phone, status, created_at, updated_at)
                VALUES (?, ?, ?, 5.00, 'Vetements', 'Aminata', '+221701234567', 'ACCEPTED', now(), now())
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, announcement);
            ps.setObject(3, asker);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedAnnouncement() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  arrival_date, available_kg, price_per_kg, transport_mode, total_kg,
                  pickup_address_label, pickup_lat, pickup_lng,
                  delivery_address_label, delivery_lat, delivery_lng, status, created_at, updated_at)
                VALUES (?, ?, 'Paris', 'Dakar', CURRENT_DATE + 10, CURRENT_DATE + 11,
                  10, 5, 'PLANE', 10, '12 rue de Paris', 48.8566, 2.3522,
                  'Plateau, Dakar', 14.69, -17.44, 'ACTIVE', now(), now())
                """)) {
            ps.setObject(1, id);
            ps.setObject(2, owner == null ? asker : owner);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedUser() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, firebase_uid, username) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "v383-" + id);
            ps.setString(3, ("u" + id.toString().replace("-", "")).substring(0, 20));
            ps.executeUpdate();
        }
        return id;
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }
}
