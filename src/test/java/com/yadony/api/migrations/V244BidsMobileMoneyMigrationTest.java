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
 * V244 — rail mobile money sur les bids : {@code payment_method} accepte désormais
 * {@code MOBILE_MONEY}, la colonne {@code mobile_money_phone} est élargie pour porter une
 * valeur chiffrée ({@code EncryptedStringConverter}, bien plus longue qu'un MSISDN en clair),
 * et les valeurs {@code WAVE}/{@code ORANGE_MONEY} legacy (jamais payables, saisies en clair
 * avant l'introduction de ce chiffrement) sont vidées pour ne pas faire échouer le
 * déchiffrement au chargement de l'entité.
 *
 * <p>Le profil "test" tourne sur H2 avec Flyway désactivé : les migrations n'y sont jamais
 * exécutées. On démarre donc un PostgreSQL embarqué (zonky, même dépendance que les autres
 * suites {@code V*MigrationTest}), on migre jusqu'à V243, on sème des données représentatives
 * de l'état legacy, puis on applique V244 et on vérifie le résultat sur le vrai moteur de
 * contraintes PostgreSQL (CHECK, largeur de colonne, index) — H2 ne les porte pas toutes.
 *
 * <p>Helpers de seed repris de {@link V241PawapayOperationsMigrationTest} : le schéma de
 * {@code users}/{@code announcements}/{@code bids} est stable entre V241 et V243 (V242
 * ajoute des colonnes à {@code users}, V243 à {@code payments} — aucune des deux n'ajoute de
 * colonne NOT NULL sans DEFAULT sur les tables ici seedées).
 */
class V244BidsMobileMoneyMigrationTest {

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
    void resetSchemaUpToV243() {
        Flyway upTo243 = flywayUpTo("243");
        upTo243.clean();
        upTo243.migrate();
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

    private void migrateToV244() {
        flywayUpTo("244").migrate();
    }

    // ─── Contrainte payment_method ────────────────────────────────────────────────

    @Test
    void beforeV244_mobileMoneyPaymentMethod_isRejectedByCheckConstraint() throws Exception {
        UUID senderId = seedUser();
        UUID announcementId = seedAnnouncement(seedUser());

        assertThatThrownBy(() -> seedBid(announcementId, senderId, "MOBILE_MONEY", null, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("bids_payment_method_check");
    }

    @Test
    void afterV244_mobileMoneyPaymentMethod_isAccepted() throws Exception {
        UUID senderId = seedUser();
        UUID announcementId = seedAnnouncement(seedUser());
        migrateToV244();

        UUID bidId = seedBid(announcementId, senderId, "MOBILE_MONEY", "encrypted-payload", "SN");

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT payment_method FROM bids WHERE id = '" + bidId + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("MOBILE_MONEY");
        }
    }

    @Test
    void afterV244_historicalPaymentMethodValues_remainAccepted() throws Exception {
        // Contrôle négatif : la contrainte élargie ne doit pas s'être resserrée sur les
        // valeurs encore utilisées (STRIPE/CASH) ou legacy (WAVE/ORANGE_MONEY).
        UUID senderId = seedUser();
        UUID announcementId = seedAnnouncement(seedUser());
        migrateToV244();

        for (String method : new String[] {"STRIPE", "CASH", "WAVE", "ORANGE_MONEY"}) {
            seedBid(announcementId, senderId, method, null, null);
        }
        // pas d'exception : les 4 valeurs historiques restent acceptées.
    }

    // ─── Nettoyage des valeurs legacy WAVE/ORANGE_MONEY ──────────────────────────

    @Test
    void afterV244_legacyWaveBid_hasPhoneAndCountryCodeNulled() throws Exception {
        UUID senderId = seedUser();
        UUID announcementId = seedAnnouncement(seedUser());
        UUID bidId = seedBid(announcementId, senderId, "WAVE", "221771234567", "SN");

        migrateToV244();

        assertPhoneAndCountryCode(bidId, null, null);
    }

    @Test
    void afterV244_legacyOrangeMoneyBid_hasPhoneAndCountryCodeNulled() throws Exception {
        UUID senderId = seedUser();
        UUID announcementId = seedAnnouncement(seedUser());
        UUID bidId = seedBid(announcementId, senderId, "ORANGE_MONEY", "221771234567", "CI");

        migrateToV244();

        assertPhoneAndCountryCode(bidId, null, null);
    }

    @Test
    void afterV244_cashBidWithIncidentalPhoneValue_isNotTouchedByLegacyCleanup() throws Exception {
        // Contrôle négatif du bloc 2 de la migration : le WHERE payment_method IN
        // ('WAVE','ORANGE_MONEY') ne doit vider aucune autre ligne.
        UUID senderId = seedUser();
        UUID announcementId = seedAnnouncement(seedUser());
        UUID bidId = seedBid(announcementId, senderId, "CASH", "221771234567", "SN");

        migrateToV244();

        assertPhoneAndCountryCode(bidId, "221771234567", "SN");
    }

    // ─── Élargissement de la colonne mobile_money_phone ──────────────────────────

    @Test
    void beforeV244_longEncryptedLikeValue_isRejectedByColumnWidth() throws Exception {
        UUID senderId = seedUser();
        UUID announcementId = seedAnnouncement(seedUser());
        String tooLong = "x".repeat(40); // > VARCHAR(30), la largeur d'avant V244

        assertThatThrownBy(() -> seedBid(announcementId, senderId, "STRIPE", tooLong, null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void afterV244_longEncryptedLikeValue_fitsInWidenedColumn() throws Exception {
        UUID senderId = seedUser();
        UUID announcementId = seedAnnouncement(seedUser());
        migrateToV244();

        // Simule un chiffré AES-256-GCM (base64), largement plus long qu'un MSISDN en clair.
        String cipherLike = "v1:" + "A".repeat(120);
        UUID bidId = seedBid(announcementId, senderId, "MOBILE_MONEY", cipherLike, "SN");

        assertPhoneAndCountryCode(bidId, cipherLike, "SN");
    }

    // ─── Index d'expiration des bids en attente de paiement ──────────────────────

    @Test
    void afterV244_awaitingPaymentExpiryIndex_exists() throws Exception {
        migrateToV244();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT indexname FROM pg_indexes WHERE tablename = 'bids' "
                             + "AND indexname = 'idx_bids_awaiting_payment_expiry'")) {
            assertThat(rs.next()).as("l'index partiel doit exister après V244").isTrue();
        }
    }

    // ─── Helpers de seed (repris de V241PawapayOperationsMigrationTest) ──────────

    private UUID seedUser() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'ACTIVE', now(), now())
                    """.formatted(id, "uid-" + id.toString().substring(0, 8), "user-" + id.toString().substring(0, 8)));
        }
        return id;
    }

    private UUID seedAnnouncement(UUID travelerId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
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

    private UUID seedBid(UUID announcementId, UUID senderId, String paymentMethod,
                          String mobileMoneyPhone, String mobileMoneyCountryCode) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description, "
                             + "recipient_name, recipient_phone, status, payment_method, "
                             + "mobile_money_phone, mobile_money_country_code, created_at, updated_at) "
                             + "VALUES (?, ?, ?, 3.00, 'Vetements', 'Aminata', '+221701234567', 'PENDING', "
                             + "?, ?, ?, now(), now())")) {
            statement.setObject(1, id);
            statement.setObject(2, announcementId);
            statement.setObject(3, senderId);
            statement.setString(4, paymentMethod);
            statement.setString(5, mobileMoneyPhone);
            statement.setString(6, mobileMoneyCountryCode);
            statement.executeUpdate();
        }
        return id;
    }

    private void assertPhoneAndCountryCode(UUID bidId, String expectedPhone, String expectedCountryCode)
            throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT mobile_money_phone, mobile_money_country_code FROM bids WHERE id = '" + bidId + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo(expectedPhone);
            assertThat(rs.getString(2)).isEqualTo(expectedCountryCode);
        }
    }
}
