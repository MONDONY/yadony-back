package com.yadony.api.matching;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
 * V382 (bateau) sur un vrai PostgreSQL : fourchette d'arrivée, capacité et prix en m³, dimensions
 * du colis, volume réservé et table des retards. Les lignes existantes sont semées à V381 puis
 * relues après la migration : elles restent intactes (toutes les colonnes neuves sont NULL).
 */
@DisplayName("Bateau - schéma V382")
class BoatSchemaIT {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static UUID traveler;
    private static UUID legacyAnnouncement;
    private static UUID legacyRequest;
    private static UUID legacyBid;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        exec("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        flywayUpTo("381").migrate();

        traveler = seedUser();
        legacyAnnouncement = insertAnnouncement("PLANE", 10, 1, null, null, null, null);
        legacyRequest = insertRequest(null, null, null, null);
        legacyBid = seedBid(legacyAnnouncement, traveler);

        flywayUpTo("latest").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    // ─── Fourchette d'arrivée ────────────────────────────────────────────────────

    @Test
    @DisplayName("une fourchette valide est acceptée, y compris à départ + 120 jours")
    void arrivalDateMax_validRanges_accepted() throws Exception {
        assertThat(insertAnnouncement("BOAT", 10, 40, 40, null, null, null)).isNotNull();
        assertThat(insertAnnouncement("BOAT", 10, 40, 120, null, null, null)).isNotNull();
    }

    @Test
    @DisplayName("une fin de fourchette avant l'arrivée est refusée")
    void arrivalDateMaxBeforeArrivalDate_isRejected() {
        assertThatThrownBy(() -> insertAnnouncement("BOAT", 10, 40, 30, null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_arrival_date_max");
    }

    @Test
    @DisplayName("une fin de fourchette au-delà de départ + 120 jours est refusée")
    void arrivalDateMaxBeyond120Days_isRejected() {
        assertThatThrownBy(() -> insertAnnouncement("BOAT", 10, 40, 121, null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_arrival_date_max");
    }

    @Test
    @DisplayName("une fin de fourchette sans date d'arrivée est refusée")
    void arrivalDateMaxWithoutArrivalDate_isRejected() {
        assertThatThrownBy(() -> insertAnnouncement("BOAT", 10, null, 40, null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_arrival_date_max");
    }

    // ─── Volume et prix au m³ ────────────────────────────────────────────────────

    @Test
    @DisplayName("capacité, volume restant (même à 0) et prix au m³ sont acceptés")
    void volumeColumns_validValues_accepted() throws Exception {
        assertThat(insertAnnouncement("BOAT", 10, 40, 50, "12.500", "0.000", "180.00")).isNotNull();
    }

    @Test
    @DisplayName("un volume restant négatif ou un prix au m³ nul sont refusés")
    void volumeColumns_invalidValues_rejected() {
        assertThatThrownBy(() -> insertAnnouncement("BOAT", 10, 40, 50, "12.500", "-0.001", "180.00"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_volume");
        assertThatThrownBy(() -> insertAnnouncement("BOAT", 10, 40, 50, "12.500", "1.000", "0.00"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_volume");
    }

    @Test
    @DisplayName("capacité nulle ou négative, restant au-dessus de la capacité ou sans capacité : refusés")
    void volumeColumns_capacityRules() {
        assertThatThrownBy(() -> insertAnnouncement("BOAT", 10, 40, 50, "0.000", "0.000", "180.00"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_volume");
        assertThatThrownBy(() -> insertAnnouncement("BOAT", 10, 40, 50, "2.000", "2.001", "180.00"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_volume");
        // Trajet bateau au kilo : un volume restant sans capacité (ou l'inverse) n'a pas de sens.
        assertThatThrownBy(() -> insertAnnouncement("BOAT", 10, 40, 50, null, "1.000", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_volume");
        assertThatThrownBy(() -> insertAnnouncement("BOAT", 10, 40, 50, "1.000", null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_volume");
    }

    @Test
    @DisplayName("trajet bateau au kilo avec un volume facultatif : accepté")
    void boatPerKgWithOptionalVolume_accepted() throws Exception {
        assertThat(insertAnnouncement("BOAT", 10, 40, 50, "2.000", "2.000", null)).isNotNull();
    }

    // ─── Kilos facultatifs pour le seul trajet bateau au m³ ──────────────────────

    @Test
    @DisplayName("capacité, capacité initiale et prix au kilo sont nullables")
    void kgColumns_areNullable() throws Exception {
        assertNullable("announcements", "available_kg");
        assertNullable("announcements", "total_kg");
        assertNullable("announcements", "price_per_kg");
    }

    @Test
    @DisplayName("un trajet bateau au m³ sans aucun kilo est accepté")
    void boatPerM3WithoutKg_accepted() throws Exception {
        UUID id = insertTrip("BOAT", 10, 40, 50, null, null, null, "2.000", "2.000", "600.00");
        assertThat(countWhere("announcements", "id = '" + id + "' AND available_kg IS NULL "
                + "AND total_kg IS NULL AND price_per_kg IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("hors trajet bateau au m³, un kilo manquant est refusé")
    void missingKgOutsideBoatPerM3_rejected() {
        assertThatThrownBy(() -> insertTrip("PLANE", 10, null, null, null, "3.00", "15.00", null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_kg_or_m3");
        assertThatThrownBy(() -> insertTrip("BOAT", 10, 40, 50, "3.00", null, "15.00", null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_kg_or_m3");
        assertThatThrownBy(() -> insertTrip("BOAT", 10, 40, 50, "3.00", "3.00", null, null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_kg_or_m3");
    }

    @Test
    @DisplayName("prix au m³ et kilos ensemble, ou prix au m³ hors bateau : refusés")
    void perM3WithKgOrOutsideBoat_rejected() {
        assertThatThrownBy(() -> insertTrip("BOAT", 10, 40, 50, "3.00", "3.00", "15.00", "2.000", "2.000", "600.00"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_kg_or_m3");
        assertThatThrownBy(() -> insertTrip("BOAT", 10, 40, 50, null, null, "15.00", "2.000", "2.000", "600.00"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_kg_or_m3");
        assertThatThrownBy(() -> insertTrip("CAR", 10, 4, null, null, null, null, "2.000", "2.000", "600.00"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_");
    }

    @Test
    @DisplayName("un trajet bateau au m³ sans volume est refusé")
    void boatPerM3WithoutVolume_rejected() {
        assertThatThrownBy(() -> insertTrip("BOAT", 10, 40, 50, null, null, null, null, null, "600.00"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_kg_or_m3");
    }

    @Test
    @DisplayName("fourchette ou volume sur un trajet qui n'est pas en bateau : refusés")
    void boatColumnsOutsideBoat_rejected() {
        assertThatThrownBy(() -> insertAnnouncement("CAR", 10, 4, 6, null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_boat_columns");
        assertThatThrownBy(() -> insertAnnouncement("PLANE", 10, null, null, "2.000", "2.000", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_boat_columns");
    }

    @Test
    @DisplayName("les CHECK des kilos gardent leurs règles pour une valeur renseignée")
    void kgChecks_stillApplyToValues() {
        assertThatThrownBy(() -> insertTrip("PLANE", 10, null, null, "-1.00", "3.00", "15.00", null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_available_kg");
        assertThatThrownBy(() -> insertTrip("PLANE", 10, null, null, "3.00", "3.00", "0.00", null, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_price_per_kg");
    }

    @Test
    @DisplayName("volume d'une demande ou d'une réservation : strictement positif")
    void requestAndBidVolume_mustBePositive() {
        assertThatThrownBy(() -> insertRequest(60, 40, 40, "0.000"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_package_requests_volume");
        assertThatThrownBy(() -> exec("UPDATE bids SET volume_m3 = 0 WHERE id = '" + legacyBid + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_bids_volume");
    }

    @Test
    @DisplayName("les colonnes de volume ont la précision attendue")
    void volumeColumns_haveExpectedTypes() throws Exception {
        assertNumeric("announcements", "volume_capacity_m3", 8, 3);
        assertNumeric("announcements", "available_volume_m3", 8, 3);
        assertNumeric("announcements", "price_per_m3", 10, 2);
        assertNumeric("package_requests", "volume_m3", 8, 3);
        assertNumeric("bids", "volume_m3", 8, 3);
    }

    // ─── Dimensions du colis ─────────────────────────────────────────────────────

    @Test
    @DisplayName("dimensions complètes (1 à 400 cm) ou absentes : acceptées")
    void dimensions_completeOrAbsent_accepted() throws Exception {
        assertThat(insertRequest(60, 40, 40, "0.096")).isNotNull();
        assertThat(insertRequest(1, 400, 400, null)).isNotNull();
        assertThat(insertRequest(null, null, null, null)).isNotNull();
    }

    @Test
    @DisplayName("des dimensions partielles sont refusées")
    void partialDimensions_areRejected() {
        assertThatThrownBy(() -> insertRequest(60, null, 40, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_package_requests_dimensions");
        assertThatThrownBy(() -> insertRequest(null, 40, null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_package_requests_dimensions");
    }

    @Test
    @DisplayName("une dimension hors de 1 à 400 cm est refusée")
    void outOfRangeDimensions_areRejected() {
        assertThatThrownBy(() -> insertRequest(0, 40, 40, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_package_requests_dimensions");
        assertThatThrownBy(() -> insertRequest(60, 401, 40, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_package_requests_dimensions");
    }

    // ─── Retards ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("un retard avec un motif connu est accepté, un motif inconnu refusé")
    void tripDelay_reasonCheck() throws Exception {
        UUID ann = insertAnnouncement("BOAT", 10, 40, 50, null, null, null);
        for (String reason : new String[] {"PORT_CONGESTION", "WEATHER", "EXTENDED_STOPOVER", "TRANSPORT_DISRUPTION", "OTHER"}) {
            insertDelay(ann, reason);
        }
        assertThatThrownBy(() -> insertDelay(ann, "FLIGHT_CANCELLED"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_trip_delays_reason");
    }

    @Test
    @DisplayName("un retard exige une nouvelle fourchette, un auteur et un trajet existant")
    void tripDelay_requiredColumns() {
        UUID unknownAnnouncement = UUID.randomUUID();
        assertThatThrownBy(() -> insertDelay(unknownAnnouncement, "WEATHER"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("trip_delays_announcement_id_fkey");
        assertThatThrownBy(() -> exec("INSERT INTO trip_delays (id, announcement_id, reason, new_arrival_date, "
                + "new_arrival_date_max, created_by, created_at, updated_at) VALUES ('" + UUID.randomUUID()
                + "', '" + legacyAnnouncement + "', 'OTHER', NULL, CURRENT_DATE, '" + traveler + "', now(), now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("new_arrival_date");
    }

    @Test
    @DisplayName("un retard dont la nouvelle fin de fourchette précède le début est refusé")
    void tripDelay_rangeCheck() {
        assertThatThrownBy(() -> exec("INSERT INTO trip_delays (id, announcement_id, reason, new_arrival_date, "
                + "new_arrival_date_max, created_by, created_at, updated_at) VALUES ('" + UUID.randomUUID()
                + "', '" + legacyAnnouncement + "', 'OTHER', CURRENT_DATE + 30, CURRENT_DATE + 29, '" + traveler
                + "', now(), now())"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_trip_delays_range");
    }

    @Test
    @DisplayName("l'index des retards par trajet existe")
    void tripDelay_indexPresent() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_trip_delays_announcement'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("announcement_id").contains("created_at DESC");
        }
    }

    // ─── Lignes existantes ───────────────────────────────────────────────────────

    @Test
    @DisplayName("les lignes existantes n'ont aucune des colonnes neuves")
    void existingRowsUntouched() throws Exception {
        assertThat(countWhere("announcements", "id = '" + legacyAnnouncement + "' AND (arrival_date_max IS NOT NULL "
                + "OR volume_capacity_m3 IS NOT NULL OR available_volume_m3 IS NOT NULL OR price_per_m3 IS NOT NULL)"))
                .isZero();
        assertThat(countWhere("announcements", "arrival_date_max IS NOT NULL AND transport_mode <> 'BOAT'")).isZero();
        assertThat(countWhere("package_requests", "id = '" + legacyRequest + "' AND (length_cm IS NOT NULL "
                + "OR width_cm IS NOT NULL OR height_cm IS NOT NULL OR volume_m3 IS NOT NULL)")).isZero();
        assertThat(countWhere("bids", "id = '" + legacyBid + "' AND volume_m3 IS NOT NULL")).isZero();
    }

    // ─── Aides ───────────────────────────────────────────────────────────────────

    /**
     * Trajet de test. Un prix au m³ en fait un trajet au m³ : ses kilos sont alors nuls (règle de
     * chk_announcements_kg_or_m3) ; sinon il porte 3 kg à 15 €/kg.
     */
    private static UUID insertAnnouncement(String mode, int departureOffset, Integer arrivalOffset,
                                           Integer arrivalMaxOffset, String capacityM3, String availableM3,
                                           String pricePerM3) throws SQLException {
        boolean perM3 = pricePerM3 != null;
        return insertTrip(mode, departureOffset, arrivalOffset, arrivalMaxOffset,
                perM3 ? null : "3.00", perM3 ? null : "3.00", perM3 ? null : "15.00",
                capacityM3, availableM3, pricePerM3);
    }

    /** Trajet dont chaque colonne de capacité et de prix est donnée telle quelle (null = NULL). */
    private static UUID insertTrip(String mode, int departureOffset, Integer arrivalOffset, Integer arrivalMaxOffset,
                                   String availableKg, String totalKg, String pricePerKg,
                                   String capacityM3, String availableM3, String pricePerM3) throws SQLException {
        UUID id = UUID.randomUUID();
        // Départ à J+departureOffset ; arrivée et fin de fourchette en jours après le départ.
        String arrival = arrivalOffset == null ? "NULL" : "CURRENT_DATE + " + (departureOffset + arrivalOffset);
        String arrivalMax = arrivalMaxOffset == null ? "NULL" : "CURRENT_DATE + " + (departureOffset + arrivalMaxOffset);
        boolean boatColumns = capacityM3 != null || availableM3 != null || pricePerM3 != null
                || arrivalMaxOffset != null || availableKg == null || totalKg == null || pricePerKg == null;
        String sql = boatColumns
                ? """
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  arrival_date, arrival_date_max, volume_capacity_m3, available_volume_m3, price_per_m3,
                  available_kg, price_per_kg, transport_mode, total_kg,
                  pickup_address_label, pickup_lat, pickup_lng,
                  delivery_address_label, delivery_lat, delivery_lng, status, created_at, updated_at)
                VALUES (?, ?, 'Marseille', 'Alger', CURRENT_DATE + %d, %s, %s, %s, %s, %s,
                  %s, %s, ?, %s, '12 rue de Paris', 48.8566, 2.3522,
                  'Plateau, Alger', 36.75, 3.05, 'ACTIVE', now(), now())
                """.formatted(departureOffset, arrival, arrivalMax, num(capacityM3), num(availableM3), num(pricePerM3),
                        num(availableKg), num(pricePerKg), num(totalKg))
                : """
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  arrival_date, available_kg, price_per_kg, transport_mode, total_kg,
                  pickup_address_label, pickup_lat, pickup_lng,
                  delivery_address_label, delivery_lat, delivery_lng, status, created_at, updated_at)
                VALUES (?, ?, 'Marseille', 'Alger', CURRENT_DATE + %d, %s,
                  %s, %s, ?, %s, '12 rue de Paris', 48.8566, 2.3522,
                  'Plateau, Alger', 36.75, 3.05, 'ACTIVE', now(), now())
                """.formatted(departureOffset, arrival, availableKg, pricePerKg, totalKg);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setObject(2, traveler);
            ps.setString(3, mode);
            ps.executeUpdate();
        }
        return id;
    }

    private static String num(String value) {
        return value == null ? "NULL" : value;
    }

    private static UUID insertRequest(Integer l, Integer w, Integer h, String volume) throws SQLException {
        UUID id = UUID.randomUUID();
        boolean newColumns = l != null || w != null || h != null || volume != null;
        String sql = newColumns
                ? """
                INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date,
                  weight_kg, parcel_size, content_category, transport_mode, status,
                  length_cm, width_cm, height_cm, volume_m3)
                VALUES (?, ?, 'Marseille', 'Alger', CURRENT_DATE + 10, 12.00, 'LARGE', 'CLOTHES', 'BOAT', 'OPEN',
                  ?, ?, ?, ?::numeric)
                """
                : """
                INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date,
                  weight_kg, parcel_size, content_category, transport_mode, status)
                VALUES (?, ?, 'Marseille', 'Alger', CURRENT_DATE + 10, 12.00, 'LARGE', 'CLOTHES', 'PLANE', 'OPEN')
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setObject(2, traveler);
            if (newColumns) {
                ps.setObject(3, l, java.sql.Types.INTEGER);
                ps.setObject(4, w, java.sql.Types.INTEGER);
                ps.setObject(5, h, java.sql.Types.INTEGER);
                ps.setString(6, volume);
            }
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedBid(UUID announcementId, UUID senderId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
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

    private static void insertDelay(UUID announcementId, String reason) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO trip_delays (id, announcement_id, reason, previous_arrival_date,
                  previous_arrival_date_max, new_arrival_date, new_arrival_date_max, message, created_by,
                  created_at, updated_at)
                VALUES (?, ?, ?, CURRENT_DATE + 40, CURRENT_DATE + 50, CURRENT_DATE + 55, CURRENT_DATE + 65,
                  'Retard au port', ?, now(), now())
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, announcementId);
            ps.setString(3, reason);
            ps.setObject(4, traveler);
            ps.executeUpdate();
        }
    }

    private static UUID seedUser() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, firebase_uid, username) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "v382-" + id);
            ps.setString(3, ("u" + id.toString().replace("-", "")).substring(0, 20));
            ps.executeUpdate();
        }
        return id;
    }

    private static long countWhere(String table, String where) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table + " WHERE " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void assertNumeric(String table, String column, int precision, int scale) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT data_type, numeric_precision, numeric_scale, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s.%s présente", table, column).isTrue();
                assertThat(rs.getString(1)).isEqualTo("numeric");
                assertThat(rs.getInt(2)).isEqualTo(precision);
                assertThat(rs.getInt(3)).isEqualTo(scale);
                assertThat(rs.getString(4)).isEqualTo("YES");
                assertThat(rs.getString(5)).isNull();
            }
        }
    }

    private static void assertNullable(String table, String column) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("%s.%s présente", table, column).isTrue();
                assertThat(rs.getString(1)).as("%s.%s nullable", table, column).isEqualTo("YES");
            }
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }
}
