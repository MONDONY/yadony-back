package com.yadony.api.dac7;

import com.yadony.api.dac7.Dac7Report.AnomalyType;
import com.yadony.api.dac7.Dac7Report.CurrencyAmounts;
import com.yadony.api.dac7.Dac7Report.QuarterReport;
import com.yadony.api.dac7.Dac7Report.RefundToReconcile;
import com.yadony.api.dac7.Dac7Report.TravelerReport;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Calcul DAC7 sur PostgreSQL avec la chaîne Flyway complète et de vraies lignes : chaque requête du modèle de lecture
 * est écrite contre le schéma réel, une colonne renommée doit casser ce test et non la déclaration.
 *
 * <p>Un voyageur par scénario (tous résidents français, identité complète, sauf mention) :
 * <ul>
 *   <li>{@code FRENCH} : carte au 31/03 23:59:59 UTC (T1) ; XOF au 01/04 00:00 (T2) ; espèces poids + grille et espèces
 *       à commission introuvable (T2) ; carte remboursée APRÈS versement (T2, non déduite) ; supplément carte (T3) ;
 *       partage admin terminé (T4, daté de sa fin) et partage en attente (non compté) ; fil carte (T4) ; carte de
 *       décembre remboursée en deux fois, le 28/12 et le 05/01/2027 (T4, non déduite, deux lignes à réconcilier) ;</li>
 *   <li>{@code AMEND} : modification carte versée, modification espèces acceptée, modification espèces refusée (exclue),
 *       supplément espèces payé, tous sur des colis du T2 ;</li>
 *   <li>{@code VOUCHER} : bon de parrainage à 0,5 consommé sur un colis carte (T1) et sur le colis d'un fil (T2) ;</li>
 *   <li>{@code THREAD_CASH} : fil négocié en F CFA payé en espèces, commission seulement dans l'audit du fil (T2) ;</li>
 *   <li>{@code CURRENCY} : colis espèces en F CFA sur un trajet resté en euros, livraison non datée (T3, estimée) ;</li>
 *   <li>{@code BEFORE} : carte remboursée en partie AVANT versement (T3, déduite) ;</li>
 *   <li>{@code DELETED} : compte supprimé payé (revue d'identité) ; {@code PRO} : professionnel immatriculé en Belgique ;
 *       {@code IVORIAN} : hors périmètre.</li>
 * </ul>
 * Hors calcul aussi : un colis espèces en transit, un paiement de 2025, un paiement supprimé.
 */
@DisplayName("DAC7 : calcul annuel sur la chaîne Flyway complète")
class Dac7ReportPostgresIT {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static Dac7ReportReadModel readModel;
    private static Dac7Report report;

    private static final UUID FRENCH = UUID.randomUUID();
    private static final UUID AMEND = UUID.randomUUID();
    private static final UUID VOUCHER = UUID.randomUUID();
    private static final UUID THREAD_CASH = UUID.randomUUID();
    private static final UUID CURRENCY = UUID.randomUUID();
    private static final UUID BEFORE = UUID.randomUUID();
    private static final UUID DELETED = UUID.randomUUID();
    private static final UUID IVORIAN = UUID.randomUUID();
    private static final UUID PRO = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static UUID cashWithoutCommission;
    private static UUID refundedAfterRelease;
    private static UUID refundedTwice;
    private static UUID pendingSplit;
    private static UUID currencyBid;

    @BeforeAll
    static void seed() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        readModel = new Dac7ReportReadModel(new NamedParameterJdbcTemplate(dataSource));

        for (UUID id : List.of(FRENCH, AMEND, VOUCHER, THREAD_CASH, CURRENCY, BEFORE, DELETED, SENDER)) {
            user(id, "FR");
        }
        user(IVORIAN, "CI");
        user(PRO, "CI");
        jdbc.update("UPDATE users SET traveler_status = 'PROFESSIONAL', business_name = 'Diallo Express', "
                + "business_registration_number = 'enc', business_country = 'BE' WHERE id = ?", PRO);
        jdbc.update("UPDATE users SET tax_identification_number = 'chiffre' WHERE id = ?", FRENCH);

        seedFrench();
        seedAmendments();
        seedVouchers();
        seedThreadCash();
        seedCurrencyAndEstimatedDate();
        seedRefundBeforeRelease();

        // Compte supprimé (identité effacée par la finalisation) mais payé dans l'année.
        payment(bid(trip(DELETED, "EUR", "10.00"), "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "20.00",
                "1.00", "2026-02-01T10:00:00Z", null);
        jdbc.update("UPDATE users SET first_name = 'Utilisateur', last_name = 'supprimé', birth_date = NULL, "
                + "residence_street = NULL, status = 'BANNED', deleted_at = now() WHERE id = ?", DELETED);
        // Hors périmètre.
        payment(bid(trip(IVORIAN, "EUR", "10.00"), "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "100.00",
                "5.00", "2026-05-01T10:00:00Z", null);
        // Professionnel : déclaré au titre du pays de l'entreprise.
        payment(bid(trip(PRO, "EUR", "10.00"), "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "30.00", "1.50",
                "2026-07-01T10:00:00Z", null);

        report = service().annualReport(2026);
    }

    private static Dac7ReportService service() {
        return new Dac7ReportService(readModel, new Dac7ResidencyPolicy(new Dac7Properties(false, null)),
                new FixedParityExchangeRateProvider());
    }

    private static void seedFrench() throws Exception {
        UUID tripEur = trip(FRENCH, "EUR", "10.00");
        UUID tripXof = trip(FRENCH, "XOF", "5000.00");
        // Carte, dernière seconde du premier trimestre.
        payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "100.00", "5.00",
                "2026-03-31T23:59:59Z", null);
        // Mobile money XOF, première seconde du deuxième trimestre.
        payment(bid(tripXof, "COMPLETED", "MOBILE_MONEY", "XOF"), null, "PAWAPAY", "XOF", "68875.49", "3279.79",
                "2026-04-01T00:00:00Z", null);
        // Espèces : poids × prix au kilo + article de grille.
        UUID cash = bid(tripEur, "COMPLETED", "CASH", "EUR");
        jdbc.update("UPDATE bids SET weight_kg = 3.00, commission_status = 'CHARGED', "
                + "cash_commission_charged_amount = 1.75, delivered_at = TIMESTAMP '2026-05-15 09:00:00' WHERE id = ?",
                cash);
        withoutForeignKeys("INSERT INTO bid_grid_items (bid_id, announcement_grid_item_id, label_snapshot, "
                + "unit_price_net_snapshot, quantity) VALUES ('" + cash + "', '" + UUID.randomUUID()
                + "', 'Valise', 5.00, 1)");
        // Espèces négociées hors fil, commission jamais enregistrée.
        cashWithoutCommission = bid(tripEur, "COMPLETED", "CASH", "EUR");
        jdbc.update("UPDATE bids SET negotiated_net_eur = 20.00, commission_status = 'CHARGED', "
                + "delivered_at = TIMESTAMP '2026-05-16 09:00:00' WHERE id = ?", cashWithoutCommission);
        // Carte remboursée APRÈS versement (webhook charge.refunded) : le voyageur garde son versement.
        refundedAfterRelease = payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "50.00",
                "2.50", "2026-06-10T12:00:00Z", "10.00");
        refundAudit(refundedAfterRelease, "REFUND_AFTER_RELEASE", "10.00", "2026-09-01T08:00:00Z");
        // Supplément carte versé.
        jdbc.update("""
                INSERT INTO bid_supplements (id, bid_id, traveler_id, sender_id, rail, status, currency, net_amount,
                  commission_amount, gross_amount, reason_code, photo_key, expires_at, paid_out_at)
                VALUES (?, ?, ?, ?, 'STRIPE', 'PAID_OUT', 'EUR', 9.50, 0.50, 10.00, 'EXTRA_WEIGHT', 'k',
                  TIMESTAMP '2026-08-02 00:00:00', TIMESTAMP '2026-08-01 10:00:00')
                """, UUID.randomUUID(), bid(tripEur, "COMPLETED", "STRIPE", "EUR"), FRENCH, SENDER);
        // Partage admin terminé : compté à sa fin (12/10), part du voyageur.
        UUID split = payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "80.00", "4.00",
                "2026-10-10T10:00:00Z", "40.00");
        jdbc.update("INSERT INTO payment_splits (payment_id, sender_refund_amount, traveler_payout_amount, currency, "
                + "mode, status, completed_at) VALUES (?, 40.00, 36.00, 'EUR', 'REFUND_TRANSFER', 'COMPLETED', "
                + "TIMESTAMP '2026-10-12 10:00:00')", split);
        // Partage admin décidé, transfert en échec : jamais compté comme versé.
        pendingSplit = payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "70.00", "3.50",
                "2026-10-15T10:00:00Z", "30.00");
        jdbc.update("INSERT INTO payment_splits (payment_id, sender_refund_amount, traveler_payout_amount, currency, "
                + "mode, status, last_error) VALUES (?, 30.00, 36.50, 'EUR', 'REFUND_TRANSFER', 'SENDER_REFUNDED', "
                + "'transfer_failed')", pendingSplit);
        // Fil de négociation payé par carte (paiement rattaché au fil, sans bid).
        UUID thread = thread(FRENCH, "EUR");
        payment(null, thread, "STRIPE", "EUR", "60.00", "3.00", "2026-11-11T10:00:00Z", null);
        // Carte de décembre remboursée en deux fois : 10 le 28/12, puis 5 le 05/01/2027 (cumuls 10 et 15).
        refundedTwice = payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "40.00", "2.00",
                "2026-12-20T10:00:00Z", "15.00");
        refundAudit(refundedTwice, "REFUND_AFTER_RELEASE", "10.00", "2026-12-28T10:00:00Z");
        refundAudit(refundedTwice, "REFUND_AFTER_RELEASE", "15.00", "2027-01-05T10:00:00Z");
        // Hors calcul : espèces en transit, paiement de 2025, paiement supprimé.
        UUID inTransit = bid(tripEur, "IN_TRANSIT", "CASH", "EUR");
        jdbc.update("UPDATE bids SET weight_kg = 2.00, commission_status = 'CHARGED' WHERE id = ?", inTransit);
        payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "70.00", "3.50",
                "2025-12-31T23:59:59Z", null);
        UUID deleted = payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "999.00", "9.00",
                "2026-05-05T10:00:00Z", null);
        jdbc.update("UPDATE payments SET deleted_at = now() WHERE id = ?", deleted);
    }

    /** Tout au T2 : colis carte 50/2,50 et colis espèces 1 kg à 10 €/kg (commission 0,50). */
    private static void seedAmendments() {
        UUID trip = trip(AMEND, "EUR", "10.00");
        UUID card = bid(trip, "COMPLETED", "STRIPE", "EUR");
        payment(card, null, "STRIPE", "EUR", "50.00", "2.50", "2026-05-01T10:00:00Z", null);
        amendment(card, trip, "STRIPE", "PAID_OUT", "18.00", "2.00", "2026-05-02 10:00:00");
        UUID cash = bid(trip, "COMPLETED", "CASH", "EUR");
        jdbc.update("UPDATE bids SET commission_status = 'CHARGED', cash_commission_charged_amount = 0.50, "
                + "delivered_at = TIMESTAMP '2026-05-03 10:00:00' WHERE id = ?", cash);
        amendment(cash, trip, "CASH", "ACCEPTED", "9.00", "1.00", null);
        amendment(cash, trip, "CASH", "DECLINED", "100.00", "5.00", null);
        jdbc.update("""
                INSERT INTO bid_supplements (id, bid_id, traveler_id, sender_id, rail, status, currency, net_amount,
                  commission_amount, gross_amount, reason_code, photo_key, expires_at, paid_at)
                VALUES (?, ?, ?, ?, 'CASH', 'PAID', 'EUR', 4.75, 0.25, 5.00, 'OTHER', 'k',
                  TIMESTAMP '2026-05-03 00:00:00', TIMESTAMP '2026-05-02 18:00:00')
                """, UUID.randomUUID(), cash, AMEND, SENDER);
    }

    /** Bon à 0,5 : colis carte 100/10 (T1) et colis d'un fil 60/6 (T2, bon consommé sur le colis matérialisé). */
    private static void seedVouchers() throws Exception {
        UUID trip = trip(VOUCHER, "EUR", "10.00");
        UUID card = bid(trip, "COMPLETED", "STRIPE", "EUR");
        payment(card, null, "STRIPE", "EUR", "100.00", "10.00", "2026-02-01T10:00:00Z", null);
        voucher(VOUCHER, "0.50", card);
        // Bon de l'EXPÉDITEUR sur le même colis : ne change rien au voyageur.
        voucher(SENDER, "0.50", card);
        UUID thread = thread(VOUCHER, "EUR");
        UUID materialized = bid(trip, "COMPLETED", "STRIPE", "EUR");
        jdbc.update("UPDATE bids SET linked_negotiation_thread_id = ? WHERE id = ?", thread, materialized);
        payment(null, thread, "STRIPE", "EUR", "60.00", "6.00", "2026-05-01T10:00:00Z", null);
        voucher(VOUCHER, "0.50", materialized);
    }

    /** Fil en F CFA réglé en espèces : la commission n'existe que dans l'audit du fil. */
    private static void seedThreadCash() throws Exception {
        UUID trip = trip(THREAD_CASH, "EUR", "10.00");
        UUID thread = thread(THREAD_CASH, "XOF");
        UUID cash = bid(trip, "COMPLETED", "CASH", "EUR");
        jdbc.update("UPDATE bids SET linked_negotiation_thread_id = ?, negotiated_net_eur = 65595.70, "
                + "commission_status = 'CHARGED', commission_charged_via = 'WALLET', "
                + "delivered_at = TIMESTAMP '2026-06-01 10:00:00' WHERE id = ?", thread, cash);
        jdbc.update("INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload, created_at) "
                + "VALUES ('NEGOTIATION_THREAD', ?, 'CASH_COMMISSION_CHARGED', ?, "
                + "'{\"commission\": \"3279.79\", \"via\": \"WALLET\"}'::jsonb, TIMESTAMPTZ '2026-05-20 10:00:00Z')",
                thread, THREAD_CASH);
    }

    /** Colis espèces en F CFA sur un trajet resté en euros, livraison jamais datée : dernière mise à jour au 10/07. */
    private static void seedCurrencyAndEstimatedDate() {
        UUID trip = trip(CURRENCY, "EUR", "5000.00");
        currencyBid = bid(trip, "COMPLETED", "CASH", "XOF");
        jdbc.update("UPDATE bids SET weight_kg = 2.00, commission_status = 'CHARGED', "
                + "cash_commission_charged_amount = 500, delivered_at = NULL, "
                + "updated_at = TIMESTAMPTZ '2026-07-10 10:00:00Z' WHERE id = ?", currencyBid);
    }

    /** Remboursé de 20 en séquestre le 10/08, versé le 20/08 (libération forcée) : déduit du T3. */
    private static void seedRefundBeforeRelease() {
        UUID paid = payment(bid(trip(BEFORE, "EUR", "10.00"), "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR",
                "60.00", "3.00", "2026-08-20T10:00:00Z", "20.00");
        refundAudit(paid, "PAYMENT_PARTIALLY_REFUNDED", "20.00", "2026-08-10T10:00:00Z");
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** Identité complète d'un particulier (prénom, nom, date de naissance, adresse). */
    private static void user(UUID id, String country) {
        jdbc.update("INSERT INTO users (id, firebase_uid, username, country, first_name, last_name, birth_date, "
                        + "residence_street) VALUES (?, ?, ?, ?, 'Awa', 'Diallo', DATE '1990-01-01', '1 rue de Paris')",
                id, "uid-dac7-" + id, "dac7_" + id.toString().substring(0, 8), country);
    }

    private static UUID trip(UUID traveler, String currency, String pricePerKg) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  available_kg, price_per_kg, transport_mode, total_kg,
                  pickup_address_label, pickup_lat, pickup_lng,
                  delivery_address_label, delivery_lat, delivery_lng, currency, created_at, updated_at)
                VALUES (?, ?, 'Paris', 'Dakar', DATE '2026-03-01', 20.00, ?, 'PLANE', 23.00,
                  'Gare du Nord', 48.88, 2.35, 'Plateau', 14.69, -17.44, ?, now(), now())
                """, id, traveler, new BigDecimal(pricePerKg), currency);
        return id;
    }

    private static UUID bid(UUID trip, String status, String method, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description, recipient_name,
                  recipient_phone, status, payment_method, currency, created_at, updated_at)
                VALUES (?, ?, ?, 1.00, 'Vêtements', 'Aminata', '+221701234567', ?, ?, ?, now(), now())
                """, id, trip, SENDER, status, method, currency);
        return id;
    }

    private static UUID thread(UUID traveler, String currency) throws Exception {
        UUID id = UUID.randomUUID();
        withoutForeignKeys("INSERT INTO negotiation_threads (id, package_request_id, traveler_id, "
                + "traveler_travel_date, traveler_available_kg, current_price_eur, currency) VALUES ('" + id
                + "', '" + UUID.randomUUID() + "', '" + traveler + "', DATE '2026-11-12', 10, 60.00, '" + currency
                + "')");
        return id;
    }

    private static UUID payment(UUID bidId, UUID threadId, String rail, String currency, String amount,
                                String commission, String releasedAt, String refunded) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payments (id, bid_id, negotiation_thread_id, stripe_payment_intent_id, rail, amount,
                  currency, commission_amount, refunded_amount, status, escrow_released_at, legacy_destination_charge,
                  disputed, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RELEASED', CAST(? AS TIMESTAMPTZ), false, false, now(), now())
                """, id, bidId, threadId, "pi_" + id, rail, new BigDecimal(amount), currency,
                new BigDecimal(commission), refunded == null ? null : new BigDecimal(refunded), releasedAt);
        return id;
    }

    private static void amendment(UUID bidId, UUID trip, String rail, String status, String net, String commission,
                                  String paidOutAt) {
        BigDecimal gross = new BigDecimal(net).add(new BigDecimal(commission));
        jdbc.update("""
                INSERT INTO bid_amendments (id, bid_id, announcement_id, traveler_id, sender_id, rail, status, currency,
                  net_amount, commission_amount, gross_amount, expires_at, paid_out_at)
                VALUES (?, ?, ?, (SELECT traveler_id FROM announcements WHERE id = ?), ?, ?, ?, 'EUR', ?, ?, ?,
                  TIMESTAMP '2026-05-04 00:00:00', CAST(? AS TIMESTAMP))
                """, UUID.randomUUID(), bidId, trip, trip, SENDER, rail, status, new BigDecimal(net),
                new BigDecimal(commission), gross, paidOutAt);
    }

    private static void voucher(UUID user, String factor, UUID bidId) {
        jdbc.update("INSERT INTO commission_vouchers (id, user_id, factor, granted_at, expires_at, consumed_at, "
                        + "consumed_on_bid_id, source_invitation_id) VALUES (?, ?, ?, now(), now() + interval '1 year', "
                        + "now(), ?, ?)",
                UUID.randomUUID(), user, new BigDecimal(factor), bidId, UUID.randomUUID());
    }

    private static void refundAudit(UUID paymentId, String action, String cumulative, String at) {
        jdbc.update("INSERT INTO audit_log (entity_type, entity_id, action, payload, created_at) "
                        + "VALUES ('PAYMENT', ?, ?, CAST(? AS JSONB), CAST(? AS TIMESTAMPTZ))",
                paymentId, action, "{\"refundedAmount\": \"" + cumulative + "\"}", at);
    }

    /** Insertion sans contrôle des clés étrangères (référentiels hors sujet), le temps d'une transaction. */
    private static void withoutForeignKeys(String sql) throws Exception {
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            s.execute("SET LOCAL session_replication_role = 'replica'");
            s.execute(sql);
            c.commit();
        }
    }

    private static TravelerReport traveler(UUID id) {
        return report.travelers().stream().filter(t -> t.travelerId().equals(id)).findFirst().orElseThrow();
    }

    private static QuarterReport quarter(UUID id, int q) {
        return traveler(id).quarters().get(q - 1);
    }

    private static CurrencyAmounts amounts(QuarterReport quarter, String currency) {
        return quarter.byCurrency().stream().filter(c -> c.currency().equals(currency)).findFirst().orElseThrow();
    }

    // ── Périmètre et identité ────────────────────────────────────────────────

    @Test
    @DisplayName("seuls les résidents déclarables à identité complète figurent ; l'ivoirien est justifié à part")
    void perimetre() {
        assertThat(report.travelers()).extracting(TravelerReport::travelerId)
                .containsExactlyInAnyOrder(FRENCH, AMEND, VOUCHER, THREAD_CASH, CURRENCY, BEFORE, PRO);
        assertThat(report.travelers()).allSatisfy(t -> assertThat(t.identityStatus())
                .isEqualTo(Dac7IdentityStatus.COMPLETE));
        assertThat(report.nonDeclarableTravelers()).containsExactly(IVORIAN);
        assertThat(report.unknownResidenceTravelers()).isEmpty();
        assertThat(report.invalidPartnerJurisdictions()).isEmpty();
        assertThat(traveler(FRENCH).taxInfoProvided()).isTrue();
        assertThat(traveler(PRO).taxInfoProvided()).isFalse();
    }

    @Test
    @DisplayName("compte supprimé payé dans l'année : jamais déclaré en silence, sort en revue d'identité")
    void compteSupprime_revueIdentite() {
        assertThat(report.identityReviewTravelers()).singleElement().satisfies(t -> {
            assertThat(t.travelerId()).isEqualTo(DELETED);
            assertThat(t.identityStatus()).isEqualTo(Dac7IdentityStatus.INCOMPLETE);
            assertThat(t.quarters().get(0).netEur()).isEqualByComparingTo("19.00");
        });
    }

    @Test
    @DisplayName("professionnel : pays de l'entreprise (BE), une seule opération suffit")
    void professionnel() {
        assertThat(traveler(PRO).residenceCountry()).isEqualTo("BE");
        assertThat(quarter(PRO, 3).operations()).isEqualTo(1);
        assertThat(quarter(PRO, 3).netEur()).isEqualByComparingTo("28.50");
        assertThat(quarter(PRO, 3).commissionEur()).isEqualByComparingTo("1.50");
    }

    // ── Voyageur principal ───────────────────────────────────────────────────

    @Test
    @DisplayName("T1 : la dernière seconde du 31/03 UTC reste au premier trimestre ; 2025 exclu")
    void premierTrimestre() {
        assertThat(quarter(FRENCH, 1).operations()).isEqualTo(1);
        assertThat(quarter(FRENCH, 1).netEur()).isEqualByComparingTo("95.00");
        assertThat(quarter(FRENCH, 1).commissionEur()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("T2 : XOF du 01/04 00:00, espèces, remboursement après versement NON déduit, commission incomplète")
    void deuxiemeTrimestre() {
        QuarterReport q2 = quarter(FRENCH, 2);
        // XOF 65 595,70 net ; espèces 35 + 20 ; carte 47,50 gardée par le voyageur malgré le remboursement.
        assertThat(q2.operations()).isEqualTo(4);
        assertThat(amounts(q2, "XOF").net()).isEqualByComparingTo("65595.70");
        assertThat(amounts(q2, "XOF").commission()).isEqualByComparingTo("3279.79");
        assertThat(amounts(q2, "EUR").net()).isEqualByComparingTo("102.50");
        assertThat(amounts(q2, "EUR").commission()).isEqualByComparingTo("4.25");
        assertThat(amounts(q2, "EUR").refundsDeducted()).isEqualByComparingTo("0");
        assertThat(q2.netEur()).isEqualByComparingTo("202.50");
        assertThat(q2.commissionEur()).isEqualByComparingTo("9.25");
        assertThat(q2.eurComplete()).isTrue();
        assertThat(q2.commissionComplete()).as("espèces à commission introuvable").isFalse();
        assertThat(report.anomalies()).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo(AnomalyType.COMMISSION_UNKNOWN);
            assertThat(a.sourceId()).isEqualTo(cashWithoutCommission);
        });
    }

    @Test
    @DisplayName("T3 : un supplément ajoute au montant sans compter une opération")
    void troisiemeTrimestre() {
        assertThat(quarter(FRENCH, 3).operations()).isZero();
        assertThat(quarter(FRENCH, 3).netEur()).isEqualByComparingTo("9.50");
        assertThat(quarter(FRENCH, 3).commissionEur()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("T4 : partage terminé (part du voyageur), fil carte, décembre ; le partage en attente n'est pas compté")
    void quatriemeTrimestre() {
        QuarterReport q4 = quarter(FRENCH, 4);
        assertThat(q4.operations()).isEqualTo(3);
        // 36 (partage terminé) + 57 (fil) + 38 (décembre, remboursements après versement non déduits).
        assertThat(q4.netEur()).isEqualByComparingTo("131.00");
        assertThat(q4.commissionEur()).isEqualByComparingTo("9.00");
        assertThat(q4.refundsDeductedEur()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("partage admin au transfert non terminé : listé en attente, jamais compté versé")
    void partageEnAttente() {
        assertThat(report.pendingSplits()).singleElement().satisfies(p -> {
            assertThat(p.paymentId()).isEqualTo(pendingSplit);
            assertThat(p.travelerId()).isEqualTo(FRENCH);
            assertThat(p.travelerPayout()).isEqualByComparingTo("36.50");
            assertThat(p.status()).isEqualTo("SENDER_REFUNDED");
        });
    }

    @Test
    @DisplayName("remboursements après versement : à réconcilier, chacun à sa date, le partage n'y est pas")
    void remboursementsAReconcilier() {
        assertThat(report.refundsToReconcile())
                .extracting(RefundToReconcile::sourceId, r -> r.amount().toPlainString(), RefundToReconcile::refundedAt)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(refundedAfterRelease, "10.00",
                                Instant.parse("2026-09-01T08:00:00Z")),
                        org.assertj.core.groups.Tuple.tuple(refundedTwice, "10.00",
                                Instant.parse("2026-12-28T10:00:00Z")),
                        org.assertj.core.groups.Tuple.tuple(refundedTwice, "5.00",
                                Instant.parse("2027-01-05T10:00:00Z")));
        assertThat(report.refundsToReconcile()).allSatisfy(r -> assertThat(r.refundDateEstimated()).isFalse());
        assertThat(report.outOfYearRefunds()).isEmpty();
        assertThat(report.anomalies()).noneMatch(a -> a.type() == AnomalyType.REFUND_DATE_ESTIMATED);
    }

    // ── Modifications, suppléments espèces, bon, fil espèces, devise ──────────

    @Test
    @DisplayName("modifications carte versée et espèces acceptée comptées, refusée exclue ; supplément espèces compté")
    void modificationsEtSupplementEspeces() {
        QuarterReport q2 = quarter(AMEND, 2);
        // Colis carte 47,50 + modification carte 18 + colis espèces 10 + modification espèces 9 + supplément 4,75.
        assertThat(q2.operations()).isEqualTo(2);
        assertThat(q2.netEur()).isEqualByComparingTo("89.25");
        assertThat(q2.commissionEur()).isEqualByComparingTo("6.25");
        assertThat(q2.commissionComplete()).isTrue();
    }

    @Test
    @DisplayName("bon de parrainage du voyageur : la part rendue n'est pas de la commission retenue (colis et fil)")
    void bonDeParrainage() {
        // 100 payés, 10 de commission, bon 0,5 : 95 versés, 5 retenus. Le bon de l'expéditeur ne change rien.
        assertThat(quarter(VOUCHER, 1).netEur()).isEqualByComparingTo("95.00");
        assertThat(quarter(VOUCHER, 1).commissionEur()).isEqualByComparingTo("5.00");
        // Fil : 60 payés, 6 de commission, bon consommé sur le colis matérialisé : 57 et 3.
        assertThat(quarter(VOUCHER, 2).netEur()).isEqualByComparingTo("57.00");
        assertThat(quarter(VOUCHER, 2).commissionEur()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("fil négocié payé en espèces : commission lue dans l'audit du fil, devise du fil")
    void filEspeces() {
        QuarterReport q2 = quarter(THREAD_CASH, 2);
        assertThat(q2.operations()).isEqualTo(1);
        assertThat(amounts(q2, "XOF").net()).isEqualByComparingTo("65595.70");
        assertThat(amounts(q2, "XOF").commission()).isEqualByComparingTo("3279.79");
        assertThat(q2.netEur()).isEqualByComparingTo("100.00");
        assertThat(q2.commissionEur()).isEqualByComparingTo("5.00");
        assertThat(q2.commissionComplete()).isTrue();
    }

    @Test
    @DisplayName("espèces : devise du colis, pas du trajet ; date de livraison absente estimée et signalée")
    void deviseDuColisEtDateEstimee() {
        QuarterReport q3 = quarter(CURRENCY, 3);
        assertThat(q3.byCurrency()).extracting(CurrencyAmounts::currency).containsExactly("XOF");
        assertThat(amounts(q3, "XOF").net()).isEqualByComparingTo("10000.00");
        assertThat(amounts(q3, "XOF").commission()).isEqualByComparingTo("500");
        assertThat(report.anomalies()).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo(AnomalyType.DELIVERY_DATE_ESTIMATED);
            assertThat(a.sourceId()).isEqualTo(currencyBid);
        });
    }

    @Test
    @DisplayName("remboursement en séquestre (avant versement) de la même année : déduit du trimestre du versement")
    void remboursementAvantVersement() {
        QuarterReport q3 = quarter(BEFORE, 3);
        assertThat(q3.netEur()).isEqualByComparingTo("37.00");
        assertThat(q3.refundsDeductedEur()).isEqualByComparingTo("20.00");
        assertThat(q3.commissionEur()).isEqualByComparingTo("3.00");
    }

    // ── Année suivante, « déjà payé », registre ──────────────────────────────

    @Test
    @DisplayName("devise flottante sans taux historique : jamais devinée ; seul le remboursement daté de 2027 y est listé")
    void deviseFlottanteSansTaux() {
        UUID trip = trip(FRENCH, "USD", "10.00");
        UUID usd = payment(bid(trip, "COMPLETED", "STRIPE", "USD"), null, "STRIPE", "USD", "100.00", "5.00",
                "2027-03-01T10:00:00Z", null);
        Dac7Report next = service().annualReport(2027);
        QuarterReport q1 = next.travelers().stream().filter(t -> t.travelerId().equals(FRENCH)).findFirst()
                .orElseThrow().quarters().get(0);
        assertThat(q1.eurComplete()).isFalse();
        assertThat(amounts(q1, "USD").net()).isEqualByComparingTo("95.00");
        assertThat(next.anomalies()).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo(AnomalyType.EXCHANGE_RATE_MISSING);
            assertThat(a.sourceId()).isEqualTo(usd);
        });
        // L'opération de 2026 entre dans la fenêtre de 2027 par son second remboursement (05/01/2027) seulement :
        // le premier (28/12/2026) appartient à 2026.
        assertThat(next.refundsToReconcile())
                .extracting(RefundToReconcile::sourceId, r -> r.amount().toPlainString(), RefundToReconcile::refundedAt)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(refundedTwice, "5.00",
                        Instant.parse("2027-01-05T10:00:00Z")));
    }

    @Test
    @DisplayName("« déjà payé » sur le vrai schéma : carte, fil, espèces livrées, modification seule ; jamais payé")
    void dejaPaye() throws Exception {
        assertThat(readModel.hasBeenPaid(FRENCH)).isTrue();
        assertThat(readModel.hasBeenPaid(PRO)).isTrue();
        assertThat(readModel.hasBeenPaid(THREAD_CASH)).isTrue();
        assertThat(readModel.hasBeenPaid(SENDER)).isFalse();

        UUID cashOnly = UUID.randomUUID();
        user(cashOnly, "FR");
        UUID inTransit = bid(trip(cashOnly, "EUR", "10.00"), "IN_TRANSIT", "CASH", "EUR");
        assertThat(readModel.hasBeenPaid(cashOnly)).as("espèces pas encore livrées").isFalse();
        jdbc.update("UPDATE bids SET status = 'COMPLETED', delivered_at = TIMESTAMP '2026-06-01 10:00:00' "
                + "WHERE id = ?", inTransit);
        assertThat(readModel.hasBeenPaid(cashOnly)).isTrue();
        assertThat(readModel.hasBeenPaidSince(cashOnly, Instant.parse("2026-06-01T10:00:00Z"))).isTrue();
        assertThat(readModel.hasBeenPaidSince(cashOnly, Instant.parse("2026-06-01T10:00:01Z"))).isFalse();

        UUID threadOnly = UUID.randomUUID();
        user(threadOnly, "FR");
        UUID thread = thread(threadOnly, "EUR");
        assertThat(readModel.hasBeenPaid(threadOnly)).isFalse();
        payment(null, thread, "STRIPE", "EUR", "60.00", "3.00", "2026-11-11T10:00:00Z", null);
        assertThat(readModel.hasBeenPaid(threadOnly)).isTrue();
        assertThat(readModel.hasBeenPaidSince(threadOnly, Instant.parse("2027-01-01T00:00:00Z"))).isFalse();

        UUID amendmentOnly = UUID.randomUUID();
        user(amendmentOnly, "FR");
        UUID trip = trip(amendmentOnly, "EUR", "10.00");
        UUID bid = bid(trip, "IN_TRANSIT", "STRIPE", "EUR");
        assertThat(readModel.hasBeenPaid(amendmentOnly)).isFalse();
        amendment(bid, trip, "STRIPE", "PAID_OUT", "18.00", "2.00", "2026-05-02 10:00:00");
        assertThat(readModel.hasBeenPaid(amendmentOnly)).isTrue();
    }

    @Test
    @DisplayName("voyageurs lus par paquets : plus de 500 identifiants en une fois")
    void voyageursParPaquets() {
        List<UUID> ids = Stream.concat(Stream.generate(UUID::randomUUID).limit(1200), Stream.of(FRENCH, PRO)).toList();
        assertThat(readModel.travelers(ids)).extracting(Dac7ReportReadModel.TravelerRow::travelerId)
                .containsExactlyInAnyOrder(FRENCH, PRO);
    }

    @Test
    @DisplayName("registre des diligences : une ligne ajoutée ne se modifie ni ne se supprime")
    void registreImmuable() {
        Long id = jdbc.queryForObject("INSERT INTO dac7_due_diligence_log (user_id, action, channel) "
                + "VALUES (?, 'PROVIDED', 'IN_APP') RETURNING id", Long.class, FRENCH);
        assertThatThrownBy(() -> jdbc.update("UPDATE dac7_due_diligence_log SET channel = 'PUSH' WHERE id = ?", id))
                .hasMessageContaining("immuable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM dac7_due_diligence_log WHERE user_id = ?", FRENCH))
                .hasMessageContaining("immuable");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dac7_due_diligence_log WHERE user_id = ?",
                Integer.class, FRENCH)).isEqualTo(1);
    }

    @Test
    @DisplayName("le calcul ne lit jamais le NIF en clair ni d'identité : seulement sa présence")
    void aucuneIdentiteDansLeRapport() {
        assertThat(report.toString()).doesNotContain("chiffre").doesNotContain("Diallo").doesNotContain("Awa")
                .doesNotContain("1990").doesNotContain("rue de Paris");
    }
}
