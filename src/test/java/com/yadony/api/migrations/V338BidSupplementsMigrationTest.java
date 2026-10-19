package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V338 : table {@code bid_supplements}, 4e usage des opérations pawaPay et débit de commission du
 * supplément au grand livre. Le profil de test génère le schéma H2 depuis les entités : les CHECK
 * et les index partiels ne sont vérifiés qu'ici, sur un vrai PostgreSQL. Des lignes existent
 * AVANT la migration (opération pawaPay, transaction de portefeuille) pour vérifier que les
 * contraintes reposées tiennent sur une base peuplée.
 */
class V338BidSupplementsMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private UUID traveler;
    private UUID sender;
    private UUID bid;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    @BeforeEach
    void migrateWithExistingRows() throws Exception {
        Flyway baseline = flywayUpTo("337");
        baseline.clean();
        baseline.migrate();
        traveler = seedUser("t");
        sender = seedUser("s");
        UUID announcement = seedAnnouncement(traveler);
        bid = seedBid(announcement, sender);
        // Lignes antérieures : les contraintes reposées par V338 doivent les accepter.
        execute("INSERT INTO pawapay_operations (id, kind, status, amount, currency, provider, country, "
                + "msisdn, msisdn_masked, purpose, user_id, created_at, updated_at) VALUES ('" + UUID.randomUUID()
                + "', 'DEPOSIT', 'COMPLETED', 1000, 'XOF', 'ORANGE_SEN', 'SN', 'enc', '+221 •••• 67', "
                + "'WALLET_TOPUP', '" + sender + "', now(), now())");
        execute("INSERT INTO wallet_transactions (id, user_id, currency, type, amount, balance_after) VALUES ('"
                + UUID.randomUUID() + "', '" + traveler + "', 'EUR', 'TOP_UP', 10, 10)");

        flywayUpTo("338").migrate();
    }

    @Test
    void uneSeuleDemandeVivanteParColis() throws Exception {
        insertSupplement(bid, "PROPOSED", "10.00", "1.00", "11.00");
        assertThatThrownBy(() -> insertSupplement(bid, "PROPOSED", "5.00", "0.50", "5.50"))
                .hasMessageContaining("uq_bid_supplements_one_active");
    }

    @Test
    void uneDemandeCloseNeBloquePasLaSuivante() throws Exception {
        insertSupplement(bid, "DECLINED", "10.00", "1.00", "11.00");
        insertSupplement(bid, "EXPIRED", "10.00", "1.00", "11.00");
        assertThatCode(() -> insertSupplement(bid, "PROPOSED", "5.00", "0.50", "5.50")).doesNotThrowAnyException();
    }

    @Test
    void leBrutVautNetPlusCommission() {
        assertThatThrownBy(() -> insertSupplement(bid, "PROPOSED", "10.00", "1.00", "12.00"))
                .hasMessageContaining("chk_bid_supplements_amounts");
        assertThatThrownBy(() -> insertSupplement(bid, "PROPOSED", "0.00", "0.00", "0.00"))
                .hasMessageContaining("chk_bid_supplements_amounts");
    }

    @Test
    void unSupplementEspecesNeConnaitNiVersementNiPaymentIntent() {
        assertThatThrownBy(() -> execute(supplementInsert(bid, "CASH", "PAYOUT_PENDING", "10.00", "1.00", "11.00", "NULL")))
                .hasMessageContaining("chk_bid_supplements_no_payout_cash");
        assertThatThrownBy(() -> execute(supplementInsert(bid, "CASH", "PROPOSED", "10.00", "1.00", "11.00", "'pi_1'")))
                .hasMessageContaining("chk_bid_supplements_card_fields");
    }

    @Test
    void operationPawapayDeSupplement_exigeSonIdEtUneSeuleOperationVivanteParType() throws Exception {
        UUID supplement = insertSupplement(bid, "PAYMENT_PENDING", "1000", "100", "1100", "PAWAPAY");

        assertThatThrownBy(() -> execute(pawapayInsert(UUID.randomUUID(), "DEPOSIT", "ACCEPTED", "NULL")))
                .hasMessageContaining("chk_pawapay_ops_purpose_supplement");

        execute(pawapayInsert(UUID.randomUUID(), "DEPOSIT", "ACCEPTED", "'" + supplement + "'"));
        assertThatThrownBy(() -> execute(pawapayInsert(UUID.randomUUID(), "DEPOSIT", "PROCESSING", "'" + supplement + "'")))
                .hasMessageContaining("uq_pawapay_ops_live_per_supplement");
        // Un échec ne compte pas : une relance reste possible, et un autre type est libre.
        assertThatCode(() -> execute(pawapayInsert(UUID.randomUUID(), "PAYOUT", "ACCEPTED", "'" + supplement + "'")))
                .doesNotThrowAnyException();
    }

    @Test
    void debitDeCommissionDuSupplement_jamaisPositif() throws Exception {
        assertThatThrownBy(() -> execute("INSERT INTO wallet_transactions (id, user_id, currency, type, amount, "
                + "balance_after) VALUES ('" + UUID.randomUUID() + "', '" + traveler
                + "', 'EUR', 'SUPPLEMENT_COMMISSION_DEDUCTED', 2, 12)"))
                .hasMessageContaining("chk_wallet_tx_sign_matches_type");
        assertThatCode(() -> execute("INSERT INTO wallet_transactions (id, user_id, currency, type, amount, "
                + "balance_after) VALUES ('" + UUID.randomUUID() + "', '" + traveler
                + "', 'EUR', 'SUPPLEMENT_COMMISSION_DEDUCTED', -2, 8)"))
                .doesNotThrowAnyException();
    }

    // ── Données ──────────────────────────────────────────────────────────────

    private UUID insertSupplement(UUID bidId, String status, String net, String commission, String gross)
            throws SQLException {
        return insertSupplement(bidId, status, net, commission, gross, "STRIPE");
    }

    private UUID insertSupplement(UUID bidId, String status, String net, String commission, String gross,
                                  String rail) throws SQLException {
        String sql = supplementInsert(bidId, rail, status, net, commission, gross, "NULL");
        UUID id = UUID.fromString(sql.substring(sql.indexOf("VALUES ('") + 9, sql.indexOf("VALUES ('") + 45));
        execute(sql);
        return id;
    }

    private String supplementInsert(UUID bidId, String rail, String status, String net, String commission,
                                    String gross, String stripePi) {
        String currency = "PAWAPAY".equals(rail) ? "XOF" : "EUR";
        return "INSERT INTO bid_supplements (id, bid_id, traveler_id, sender_id, rail, status, currency, "
                + "net_amount, commission_amount, gross_amount, reason_code, photo_key, expires_at, "
                + "stripe_payment_intent_id) VALUES ('" + UUID.randomUUID() + "', '" + bidId + "', '" + traveler
                + "', '" + sender + "', '" + rail + "', '" + status + "', '" + currency + "', " + net + ", "
                + commission + ", " + gross + ", 'GRID_ITEM', 'tracking/" + bidId + "/1_supplement.jpg', "
                + "now() + interval '2 hours', " + stripePi + ")";
    }

    private String pawapayInsert(UUID id, String kind, String status, String supplementId) {
        return "INSERT INTO pawapay_operations (id, kind, status, amount, currency, provider, country, msisdn, "
                + "msisdn_masked, purpose, user_id, bid_supplement_id, created_at, updated_at) VALUES ('" + id
                + "', '" + kind + "', '" + status + "', 1100, 'XOF', 'ORANGE_SEN', 'SN', 'enc', '+221 •••• 67', "
                + "'BID_SUPPLEMENT', '" + sender + "', " + supplementId + ", now(), now())";
    }

    private UUID seedUser(String tag) throws SQLException {
        String suffix = tag + "-" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("INSERT INTO users (firebase_uid, username) VALUES ('v338-" + suffix
                    + "', 'v338_" + suffix.replace('-', '_') + "') RETURNING id");
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }

    private UUID seedAnnouncement(UUID travelerId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
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
        return id;
    }

    private UUID seedBid(UUID announcementId, UUID senderId) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("""
                INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description,
                  recipient_name, recipient_phone, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 3.00, 'Vetements',
                  'Aminata', '+221701234567', 'ACCEPTED', now(), now())
                """.formatted(id, announcementId, senderId));
        return id;
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
