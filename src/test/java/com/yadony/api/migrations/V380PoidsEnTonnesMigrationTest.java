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
 * V380 : les neuf colonnes de poids passent de NUMERIC(5,2) à NUMERIC(8,2) (tonnes en route et
 * en bateau) et le CHECK {@code chk_pkg_req_weight} passe de 0,5..32 à 0,5..999 999,99 ; les
 * 32 kg de l'avion et de « peu importe » restent appliqués en Java ({@code WeightLimits}).
 *
 * <p>Les lignes sont semées à V379 puis relues après la migration complète : un élargissement de
 * précision à échelle égale ne doit changer aucune valeur existante (Review Focus 2).
 */
class V380PoidsEnTonnesMigrationTest {

    private static final List<String[]> WEIGHT_COLUMNS = List.of(
            new String[] {"announcements", "available_kg"},
            new String[] {"announcements", "total_kg"},
            new String[] {"announcements", "reserved_kg"},
            new String[] {"bids", "weight_kg"},
            new String[] {"package_requests", "weight_kg"},
            new String[] {"negotiation_threads", "traveler_available_kg"},
            new String[] {"bid_amendments", "added_weight_kg"},
            new String[] {"bid_amendments", "capacity_reserved_kg"},
            new String[] {"corridor_alerts", "min_weight_kg"});

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private static UUID sender;
    private static UUID traveler;
    private static UUID legacyAnnouncement;
    private static UUID legacyBid;
    private static UUID legacyRequest;
    private static UUID legacyThread;
    private static UUID legacyAmendment;
    private static UUID legacyAlert;

    @BeforeAll
    static void migrateWithLegacyRows() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        flywayUpTo("379").migrate();

        sender = seedUser();
        traveler = seedUser();
        legacyAnnouncement = seedAnnouncement(traveler, "23.50", "31.99", "8.49");
        legacyBid = seedBid(legacyAnnouncement, sender, "23.50");
        legacyRequest = seedRequest(sender, "31.99");
        legacyThread = seedThread(legacyRequest, traveler, "999.99");
        legacyAmendment = seedAmendment(legacyBid, legacyAnnouncement, traveler, sender, "3.50", "3.25");
        legacyAlert = seedAlert(traveler, "12.75");

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

    @Test
    void weightColumns_areNumeric8_2() throws Exception {
        for (String[] column : WEIGHT_COLUMNS) {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement("""
                         SELECT data_type, numeric_precision, numeric_scale FROM information_schema.columns
                         WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                         """)) {
                ps.setString(1, column[0]);
                ps.setString(2, column[1]);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("%s.%s", column[0], column[1]).isTrue();
                    assertThat(rs.getString(1)).as("%s.%s", column[0], column[1]).isEqualTo("numeric");
                    assertThat(rs.getInt(2)).as("%s.%s précision", column[0], column[1]).isEqualTo(8);
                    assertThat(rs.getInt(3)).as("%s.%s échelle", column[0], column[1]).isEqualTo(2);
                }
            }
        }
    }

    @Test
    void untouchedColumns_keepTheirType() throws Exception {
        assertThat(dataType("trip_templates", "available_kg")).isEqualTo("integer");
        assertThat(dataType("trip_recurrences", "available_kg")).isEqualTo("double precision");
        assertThat(dataType("user_business_preferences", "default_package_weight_kg")).isEqualTo("integer");
    }

    @Test
    void existingWeights_unchangedAfterV380() throws Exception {
        assertThat(decimal("SELECT available_kg FROM announcements WHERE id = ?", legacyAnnouncement))
                .isEqualTo(new BigDecimal("23.50"));
        assertThat(decimal("SELECT total_kg FROM announcements WHERE id = ?", legacyAnnouncement))
                .isEqualTo(new BigDecimal("31.99"));
        assertThat(decimal("SELECT reserved_kg FROM announcements WHERE id = ?", legacyAnnouncement))
                .isEqualTo(new BigDecimal("8.49"));
        assertThat(decimal("SELECT weight_kg FROM bids WHERE id = ?", legacyBid))
                .isEqualTo(new BigDecimal("23.50"));
        assertThat(decimal("SELECT weight_kg FROM package_requests WHERE id = ?", legacyRequest))
                .isEqualTo(new BigDecimal("31.99"));
        assertThat(decimal("SELECT traveler_available_kg FROM negotiation_threads WHERE id = ?", legacyThread))
                .isEqualTo(new BigDecimal("999.99"));
        assertThat(decimal("SELECT added_weight_kg FROM bid_amendments WHERE id = ?", legacyAmendment))
                .isEqualTo(new BigDecimal("3.50"));
        assertThat(decimal("SELECT capacity_reserved_kg FROM bid_amendments WHERE id = ?", legacyAmendment))
                .isEqualTo(new BigDecimal("3.25"));
        assertThat(decimal("SELECT min_weight_kg FROM corridor_alerts WHERE id = ?", legacyAlert))
                .isEqualTo(new BigDecimal("12.75"));
    }

    @Test
    void packageRequestWeightCheck_isBetween0_5And999999_99() throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT pg_get_constraintdef(oid) FROM pg_constraint
                     WHERE conname = 'chk_pkg_req_weight' AND conrelid = to_regclass('public.package_requests')
                     """)) {
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("0.5").contains("999999.99");
            assertThat(rs.next()).isFalse();
        }
    }

    @Test
    void packageRequestWeight_acceptsUpTo999999_99() throws Exception {
        UUID id = seedRequest(sender, "999999.99");
        assertThat(decimal("SELECT weight_kg FROM package_requests WHERE id = ?", id))
                .isEqualTo(new BigDecimal("999999.99"));
        UUID road = seedRequest(sender, "1200.5");
        assertThat(decimal("SELECT weight_kg FROM package_requests WHERE id = ?", road))
                .isEqualTo(new BigDecimal("1200.50"));
        assertThatThrownBy(() -> seedRequest(sender, "1000000")).isInstanceOf(SQLException.class);
    }

    @Test
    void packageRequestWeight_below0_5_stillRefused() {
        assertThatThrownBy(() -> seedRequest(sender, "0.4"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_pkg_req_weight");
    }

    @Test
    void announcement_1200kg_inserted() throws Exception {
        UUID id = seedAnnouncement(traveler, "1200.5", "2500", "1299.5");
        assertThat(decimal("SELECT available_kg FROM announcements WHERE id = ?", id))
                .isEqualTo(new BigDecimal("1200.50"));
        assertThat(decimal("SELECT total_kg FROM announcements WHERE id = ?", id))
                .isEqualTo(new BigDecimal("2500.00"));
        UUID bid = seedBid(id, sender, "1200.5");
        assertThat(decimal("SELECT weight_kg FROM bids WHERE id = ?", bid)).isEqualTo(new BigDecimal("1200.50"));
        UUID amendment = seedAmendment(bid, id, traveler, sender, "1200.5", "1200.5");
        assertThat(decimal("SELECT added_weight_kg FROM bid_amendments WHERE id = ?", amendment))
                .isEqualTo(new BigDecimal("1200.50"));
        UUID thread = seedThread(seedRequest(sender, "5"), traveler, "1200.5");
        assertThat(decimal("SELECT traveler_available_kg FROM negotiation_threads WHERE id = ?", thread))
                .isEqualTo(new BigDecimal("1200.50"));
        UUID alert = seedAlert(traveler, "1200.5");
        assertThat(decimal("SELECT min_weight_kg FROM corridor_alerts WHERE id = ?", alert))
                .isEqualTo(new BigDecimal("1200.50"));
    }

    @Test
    void announcement_aboveColumnPrecision_stillRefused() {
        assertThatThrownBy(() -> seedAnnouncement(traveler, "1000000", "1000000", "0"))
                .isInstanceOf(SQLException.class);
    }

    // ─── Semences ────────────────────────────────────────────────────────────────

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

    private static UUID seedAnnouncement(UUID travelerId, String available, String total, String reserved)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                         available_kg, price_per_kg, transport_mode, total_kg, reserved_kg,
                         pickup_address_label, pickup_lat, pickup_lng,
                         delivery_address_label, delivery_lat, delivery_lng)
                     VALUES (?, ?, 'Paris', 'Dakar', CURRENT_DATE + 10, ?, 15.00, 'CAR', ?, ?,
                         '12 rue de Paris', 48.8566, 2.3522, 'Plateau, Dakar', 14.6928, -17.4467)
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, travelerId);
            ps.setBigDecimal(3, new BigDecimal(available));
            ps.setBigDecimal(4, new BigDecimal(total));
            ps.setBigDecimal(5, new BigDecimal(reserved));
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedBid(UUID announcementId, UUID senderId, String weight) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description,
                         recipient_name, recipient_phone, status, created_at, updated_at)
                     VALUES (?, ?, ?, ?, 'Vetements', 'Aminata', '+221701234567', 'ACCEPTED', now(), now())
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, announcementId);
            ps.setObject(3, senderId);
            ps.setBigDecimal(4, new BigDecimal(weight));
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedRequest(UUID senderId, String weight) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date,
                         weight_kg, parcel_size, content_category, transport_mode, status)
                     VALUES (?, ?, 'Paris', 'Dakar', CURRENT_DATE + 10, ?, 'LARGE', 'CLOTHES', 'CAR', 'OPEN')
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, senderId);
            ps.setBigDecimal(3, new BigDecimal(weight));
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedThread(UUID requestId, UUID travelerId, String availableKg) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO negotiation_threads (id, package_request_id, traveler_id, traveler_travel_date,
                         traveler_available_kg, status, current_price_eur, created_at, updated_at)
                     VALUES (?, ?, ?, CURRENT_DATE + 10, ?, 'OPEN', 42.0, now(), now())
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, requestId);
            ps.setObject(3, travelerId);
            ps.setBigDecimal(4, new BigDecimal(availableKg));
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedAmendment(UUID bidId, UUID announcementId, UUID travelerId, UUID senderId,
                                      String added, String reserved) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO bid_amendments (id, bid_id, announcement_id, traveler_id, sender_id, rail, status,
                         currency, added_weight_kg, capacity_reserved_kg, net_amount, commission_amount,
                         gross_amount, expires_at)
                     VALUES (?, ?, ?, ?, ?, 'CASH', 'ACCEPTED', 'EUR', ?, ?, 10.00, 1.00, 11.00,
                         now() + interval '1 day')
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, bidId);
            ps.setObject(3, announcementId);
            ps.setObject(4, travelerId);
            ps.setObject(5, senderId);
            ps.setBigDecimal(6, new BigDecimal(added));
            ps.setBigDecimal(7, new BigDecimal(reserved));
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedAlert(UUID ownerId, String minWeight) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO corridor_alerts (id, owner_id, departure_city, arrival_city, min_weight_kg)
                     VALUES (?, ?, 'Paris', 'Bamako', ?)
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, ownerId);
            ps.setBigDecimal(3, new BigDecimal(minWeight));
            ps.executeUpdate();
        }
        return id;
    }
}
