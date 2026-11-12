package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V381 : taille d'un colis (S, M, L, XL) sur la demande, la réservation, le trajet, le modèle et la
 * série, et poids maximal par colis sur le trajet, le modèle et la série.
 *
 * <p>Les lignes sont semées à V380 puis relues après la migration complète : les trajets, demandes
 * et réservations existants n'ont ni taille ni poids max (Review Focus 2).
 */
class V381TailleEtPoidsMaxMigrationTest {

    /** {table, colonne} des six colonnes de taille. */
    private static final List<String[]> SIZE_COLUMNS = List.of(
            new String[] {"announcements", "max_item_size"},
            new String[] {"package_requests", "item_size"},
            new String[] {"bids", "item_size"},
            new String[] {"trip_templates", "max_item_size"},
            new String[] {"trip_recurrences", "max_item_size"});

    /** {table, colonne} des colonnes de poids max par colis. */
    private static final List<String[]> WEIGHT_COLUMNS = List.of(
            new String[] {"announcements", "max_parcel_weight_kg"},
            new String[] {"trip_templates", "max_parcel_weight_kg"},
            new String[] {"trip_recurrences", "max_parcel_weight_kg"});

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private static UUID sender;
    private static UUID traveler;
    private static UUID legacyAnnouncement;
    private static UUID legacyBid;
    private static UUID legacyRequest;
    private static UUID legacyTemplate;
    private static UUID legacyRecurrence;

    @BeforeAll
    static void migrateWithLegacyRows() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        flywayUpTo("380").migrate();

        sender = seedUser();
        traveler = seedUser();
        legacyAnnouncement = seedAnnouncement(traveler);
        legacyBid = seedBid(legacyAnnouncement, sender);
        legacyRequest = seedRequest(sender);
        legacyTemplate = seedTemplate(traveler);
        legacyRecurrence = seedRecurrence(traveler);

        flywayUpTo("latest").migrate();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).load();
    }

    // ─── Colonnes ────────────────────────────────────────────────────────────────

    @Test
    void sizeColumns_areNullableVarchar2WithoutDefault() throws Exception {
        for (String[] column : SIZE_COLUMNS) {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement("""
                         SELECT data_type, character_maximum_length, is_nullable, column_default
                         FROM information_schema.columns
                         WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                         """)) {
                ps.setString(1, column[0]);
                ps.setString(2, column[1]);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("%s.%s présente", column[0], column[1]).isTrue();
                    assertThat(rs.getString(1)).as("%s.%s type", column[0], column[1]).isEqualTo("character varying");
                    assertThat(rs.getInt(2)).as("%s.%s longueur", column[0], column[1]).isEqualTo(2);
                    assertThat(rs.getString(3)).as("%s.%s nullable", column[0], column[1]).isEqualTo("YES");
                    assertThat(rs.getString(4)).as("%s.%s défaut", column[0], column[1]).isNull();
                }
            }
        }
    }

    @Test
    void announcementMaxParcelWeight_isNumeric8_2() throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT data_type, numeric_precision, numeric_scale, is_nullable, column_default
                     FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = 'announcements'
                       AND column_name = 'max_parcel_weight_kg'
                     """)) {
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("numeric");
            assertThat(rs.getInt(2)).isEqualTo(8);
            assertThat(rs.getInt(3)).isEqualTo(2);
            assertThat(rs.getString(4)).isEqualTo("YES");
            assertThat(rs.getString(5)).isNull();
        }
    }

    @Test
    void templateAndRecurrenceMaxParcelWeight_areDoublePrecision() throws Exception {
        assertThat(dataType("trip_templates", "max_parcel_weight_kg")).isEqualTo("double precision");
        assertThat(dataType("trip_recurrences", "max_parcel_weight_kg")).isEqualTo("double precision");
    }

    // ─── Lignes existantes (Review Focus 2) ──────────────────────────────────────

    @Test
    void existingRows_haveNullSizeAndMaxWeight() throws Exception {
        assertThat(string("SELECT max_item_size FROM announcements WHERE id = ?", legacyAnnouncement)).isNull();
        assertThat(string("SELECT max_parcel_weight_kg::text FROM announcements WHERE id = ?", legacyAnnouncement))
                .isNull();
        assertThat(string("SELECT item_size FROM bids WHERE id = ?", legacyBid)).isNull();
        assertThat(string("SELECT item_size FROM package_requests WHERE id = ?", legacyRequest)).isNull();
        assertThat(string("SELECT max_item_size FROM trip_templates WHERE id = ?", legacyTemplate)).isNull();
        assertThat(string("SELECT max_parcel_weight_kg::text FROM trip_templates WHERE id = ?", legacyTemplate))
                .isNull();
        assertThat(string("SELECT max_item_size FROM trip_recurrences WHERE id = ?", legacyRecurrence)).isNull();
        assertThat(string("SELECT max_parcel_weight_kg::text FROM trip_recurrences WHERE id = ?", legacyRecurrence))
                .isNull();
    }

    @Test
    void existingRows_keepTheirOtherValues() throws Exception {
        assertThat(decimal("SELECT available_kg FROM announcements WHERE id = ?", legacyAnnouncement))
                .isEqualTo(new BigDecimal("23.50"));
        assertThat(decimal("SELECT weight_kg FROM package_requests WHERE id = ?", legacyRequest))
                .isEqualTo(new BigDecimal("12.00"));
    }

    // ─── CHECK de taille ─────────────────────────────────────────────────────────

    @Test
    void itemSize_rejectsUnknownOnEveryColumn() {
        assertThatThrownBy(() -> updateSize("package_requests", "item_size", legacyRequest, "XS"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_package_requests_item_size");
        assertThatThrownBy(() -> updateSize("bids", "item_size", legacyBid, "XS"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_bids_item_size");
        assertThatThrownBy(() -> updateSize("announcements", "max_item_size", legacyAnnouncement, "XS"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_announcements_max_item_size");
        assertThatThrownBy(() -> updateSize("trip_templates", "max_item_size", legacyTemplate, "XS"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_trip_templates_max_item_size");
        assertThatThrownBy(() -> updateSize("trip_recurrences", "max_item_size", legacyRecurrence, "XS"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_trip_recurrences_max_item_size");
    }

    @Test
    void itemSize_tooLongValue_refused() {
        assertThatThrownBy(() -> updateSize("package_requests", "item_size", legacyRequest, "XXL"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void itemSize_isCaseSensitiveAndRefusesLowercaseAndBlank() {
        assertThatThrownBy(() -> updateSize("package_requests", "item_size", legacyRequest, "xl"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> updateSize("package_requests", "item_size", legacyRequest, ""))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void itemSize_acceptsSMLXLAndNull() throws Exception {
        UUID request = seedRequest(sender);
        for (String size : List.of("S", "M", "L", "XL")) {
            for (String[] column : SIZE_COLUMNS) {
                UUID row = switch (column[0]) {
                    case "announcements" -> seedAnnouncement(traveler);
                    case "package_requests" -> request;
                    case "bids" -> seedBid(seedAnnouncement(traveler), sender);
                    case "trip_templates" -> seedTemplate(traveler);
                    default -> seedRecurrence(traveler);
                };
                updateSize(column[0], column[1], row, size);
                assertThat(string("SELECT " + column[1] + " FROM " + column[0] + " WHERE id = ?", row))
                        .as("%s.%s", column[0], column[1]).isEqualTo(size);
                updateSize(column[0], column[1], row, null);
                assertThat(string("SELECT " + column[1] + " FROM " + column[0] + " WHERE id = ?", row))
                        .as("%s.%s remise à null", column[0], column[1]).isNull();
            }
        }
    }

    // ─── CHECK de poids max ──────────────────────────────────────────────────────

    @Test
    void maxParcelWeight_mustBePositive() {
        assertThatThrownBy(() -> updateWeight("announcements", legacyAnnouncement, "0"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_announcements_max_parcel_weight");
        assertThatThrownBy(() -> updateWeight("announcements", legacyAnnouncement, "-3"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> updateWeight("trip_templates", legacyTemplate, "0"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_trip_templates_max_parcel_weight");
        assertThatThrownBy(() -> updateWeight("trip_recurrences", legacyRecurrence, "0"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_trip_recurrences_max_parcel_weight");
    }

    @Test
    void maxParcelWeight_below0_5_refused_D19() {
        for (String[] column : WEIGHT_COLUMNS) {
            UUID row = switch (column[0]) {
                case "announcements" -> legacyAnnouncement;
                case "trip_templates" -> legacyTemplate;
                default -> legacyRecurrence;
            };
            assertThatThrownBy(() -> updateWeight(column[0], row, "0.49"))
                    .as("%s", column[0]).isInstanceOf(SQLException.class);
        }
    }

    @Test
    void maxParcelWeight_acceptsFrom0_5To999999_99AndNull() throws Exception {
        for (String[] column : WEIGHT_COLUMNS) {
            UUID row = switch (column[0]) {
                case "announcements" -> seedAnnouncement(traveler);
                case "trip_templates" -> seedTemplate(traveler);
                default -> seedRecurrence(traveler);
            };
            updateWeight(column[0], row, "0.5");
            assertThat(decimal("SELECT max_parcel_weight_kg::numeric FROM " + column[0] + " WHERE id = ?", row))
                    .as("%s min", column[0]).isEqualByComparingTo("0.5");
            updateWeight(column[0], row, "1500");
            updateWeight(column[0], row, "999999.99");
            assertThat(decimal("SELECT max_parcel_weight_kg::numeric FROM " + column[0] + " WHERE id = ?", row))
                    .as("%s max", column[0]).isEqualByComparingTo("999999.99");
            updateWeight(column[0], row, null);
            assertThat(string("SELECT max_parcel_weight_kg::text FROM " + column[0] + " WHERE id = ?", row))
                    .as("%s null", column[0]).isNull();
        }
    }

    @Test
    void maxParcelWeight_above999999_99_refused() {
        assertThatThrownBy(() -> updateWeight("announcements", legacyAnnouncement, "1000000"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> updateWeight("trip_templates", legacyTemplate, "1000000"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_trip_templates_max_parcel_weight");
        assertThatThrownBy(() -> updateWeight("trip_recurrences", legacyRecurrence, "1000000"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_trip_recurrences_max_parcel_weight");
    }

    // ─── Aide ────────────────────────────────────────────────────────────────────

    private static void updateSize(String table, String column, UUID id, String value) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE " + table + " SET " + column + " = ? WHERE id = ?")) {
            ps.setString(1, value);
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    private static void updateWeight(String table, UUID id, String value) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE " + table + " SET max_parcel_weight_kg = ? WHERE id = ?")) {
            if ("announcements".equals(table)) {
                ps.setBigDecimal(1, value == null ? null : new BigDecimal(value));
            } else if (value == null) {
                ps.setNull(1, java.sql.Types.DOUBLE);
            } else {
                ps.setDouble(1, Double.parseDouble(value));
            }
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    private static String dataType(String table, String column) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT data_type FROM information_schema.columns WHERE table_schema = 'public'"
                             + " AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s.%s", table, column).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static String string(String sql, UUID id) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getString(1);
            }
        }
    }

    private static BigDecimal decimal(String sql, UUID id) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getBigDecimal(1);
            }
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

    private static UUID seedAnnouncement(UUID travelerId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                         available_kg, price_per_kg, transport_mode, total_kg, reserved_kg,
                         pickup_address_label, pickup_lat, pickup_lng,
                         delivery_address_label, delivery_lat, delivery_lng)
                     VALUES (?, ?, 'Paris', 'Dakar', CURRENT_DATE + 10, 23.50, 15.00, 'CAR', 31.99, 8.49,
                         '12 rue de Paris', 48.8566, 2.3522, 'Plateau, Dakar', 14.6928, -17.4467)
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, travelerId);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedBid(UUID announcementId, UUID senderId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description,
                         recipient_name, recipient_phone, status, created_at, updated_at)
                     VALUES (?, ?, ?, 5.00, 'Vetements', 'Aminata', '+221701234567', 'ACCEPTED', now(), now())
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, announcementId);
            ps.setObject(3, senderId);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedRequest(UUID senderId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date,
                         weight_kg, parcel_size, content_category, transport_mode, status)
                     VALUES (?, ?, 'Paris', 'Dakar', CURRENT_DATE + 10, 12.00, 'LARGE', 'CLOTHES', 'CAR', 'OPEN')
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, senderId);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedTemplate(UUID userId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO trip_templates (id, user_id, label, departure_city, arrival_city, price_per_kg)
                     VALUES (?, ?, 'Modèle', 'Paris', 'Dakar', 8.0)
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedRecurrence(UUID userId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO trip_recurrences (id, user_id, departure_city, arrival_city, available_kg,
                         price_per_kg, pickup_label, pickup_lat, pickup_lng, delivery_label, delivery_lat,
                         delivery_lng, weekdays, horizon_days)
                     VALUES (?, ?, 'Paris', 'Dakar', 23, 8, '12 rue de la Paix', 48.86, 2.33,
                         'Aeroport de Dakar', 14.73, -17.49, '1000100', 14)
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, userId);
            ps.executeUpdate();
        }
        return id;
    }
}
