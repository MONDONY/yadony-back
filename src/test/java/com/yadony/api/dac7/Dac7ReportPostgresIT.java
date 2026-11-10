package com.yadony.api.dac7;

import com.yadony.api.dac7.Dac7Report.AnomalyType;
import com.yadony.api.dac7.Dac7Report.CurrencyAmounts;
import com.yadony.api.dac7.Dac7Report.QuarterReport;
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
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Calcul DAC7 sur PostgreSQL avec la chaîne Flyway complète et de vraies lignes : chaque requête du modèle de lecture
 * est écrite contre le schéma réel, une colonne renommée doit casser ce test et non la déclaration.
 *
 * <p>Jeu de données 2026 (voyageur français sauf mention) :
 * <ol>
 *   <li>carte EUR versée le 31/03 23:59:59 UTC : 100 payés, 5 de commission → T1 ;</li>
 *   <li>mobile money XOF versé le 01/04 00:00:00 UTC : 68 875,49 payés, 3 279,79 de commission (100 € net) → T2 ;</li>
 *   <li>espèces livrées le 15/05 : 3 kg à 10 €/kg + un article de grille à 5 € = 35, commission 1,75 → T2 ;</li>
 *   <li>espèces livrées le 16/05, net négocié 20, commission jamais enregistrée → T2, anomalie ;</li>
 *   <li>carte 50/2,50 versée le 10/06, remboursée de 10 le 01/09 (audit) → T2 diminué de 10 ;</li>
 *   <li>supplément carte versé le 01/08 : 9,50 net, 0,50 de commission → T3, sans opération de plus ;</li>
 *   <li>partage admin le 10/10 : 80 payés, 4 de commission, 36 au voyageur → T4 net 36, pas un remboursement ;</li>
 *   <li>fil de négociation payé par carte, versé le 11/11 : 60/3 → T4 ;</li>
 *   <li>carte 40/2 versée le 20/12, remboursée de 20 le 05/01/2027 → T4 entier, remboursement listé à part ;</li>
 * </ol>
 * Hors calcul : un voyageur ivoirien, un colis en espèces en transit, un paiement versé en 2025, un paiement supprimé.
 * Un professionnel immatriculé en Belgique (profil en Côte d'Ivoire) est déclaré au titre de la Belgique.
 */
@DisplayName("DAC7 : calcul annuel sur la chaîne Flyway complète")
class Dac7ReportPostgresIT {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static Dac7Report report;

    private static final UUID FRENCH = UUID.randomUUID();
    private static final UUID IVORIAN = UUID.randomUUID();
    private static final UUID PRO = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static UUID cashWithoutCommission;
    private static UUID refundedNextYear;
    private static UUID splitPayment;

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

        user(FRENCH, "FR");
        user(IVORIAN, "CI");
        user(SENDER, "FR");
        user(PRO, "CI");
        jdbc.update("UPDATE users SET traveler_status = 'PROFESSIONAL', business_name = 'Diallo Express', "
                + "business_registration_number = 'enc', business_country = 'BE' WHERE id = ?", PRO);
        jdbc.update("UPDATE users SET tax_identification_number = 'chiffre' WHERE id = ?", FRENCH);

        UUID tripEur = trip(FRENCH, "EUR", "10.00");
        UUID tripXof = trip(FRENCH, "XOF", "5000.00");
        UUID tripCi = trip(IVORIAN, "EUR", "10.00");
        UUID tripPro = trip(PRO, "EUR", "10.00");

        // 1. Carte, dernière seconde du premier trimestre.
        payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "100.00", "5.00",
                "2026-03-31T23:59:59Z", null);
        // 2. Mobile money XOF, première seconde du deuxième trimestre.
        payment(bid(tripXof, "COMPLETED", "MOBILE_MONEY", "XOF"), null, "PAWAPAY", "XOF", "68875.49", "3279.79",
                "2026-04-01T00:00:00Z", null);
        // 3. Espèces : poids × prix au kilo + article de grille.
        UUID cash = bid(tripEur, "COMPLETED", "CASH", "EUR");
        jdbc.update("UPDATE bids SET weight_kg = 3.00, commission_status = 'CHARGED', "
                + "cash_commission_charged_amount = 1.75, delivered_at = TIMESTAMP '2026-05-15 09:00:00' WHERE id = ?",
                cash);
        withoutForeignKeys("INSERT INTO bid_grid_items (bid_id, announcement_grid_item_id, label_snapshot, "
                + "unit_price_net_snapshot, quantity) VALUES ('" + cash + "', '" + UUID.randomUUID()
                + "', 'Valise', 5.00, 1)");
        // 4. Espèces négociées, commission jamais enregistrée (colis ancien).
        cashWithoutCommission = bid(tripEur, "COMPLETED", "CASH", "EUR");
        jdbc.update("UPDATE bids SET negotiated_net_eur = 20.00, commission_status = 'CHARGED', "
                + "delivered_at = TIMESTAMP '2026-05-16 09:00:00' WHERE id = ?", cashWithoutCommission);
        // 5. Carte remboursée la même année (audit du webhook charge.refunded).
        UUID refundedSameYear = payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "50.00",
                "2.50", "2026-06-10T12:00:00Z", "10.00");
        refundAudit(refundedSameYear, "2026-09-01T08:00:00Z");
        // 6. Supplément carte versé.
        UUID supplementBid = bid(tripEur, "COMPLETED", "STRIPE", "EUR");
        jdbc.update("""
                INSERT INTO bid_supplements (id, bid_id, traveler_id, sender_id, rail, status, currency, net_amount,
                  commission_amount, gross_amount, reason_code, photo_key, expires_at, paid_out_at)
                VALUES (?, ?, ?, ?, 'STRIPE', 'PAID_OUT', 'EUR', 9.50, 0.50, 10.00, 'EXTRA_WEIGHT', 'k',
                  TIMESTAMP '2026-08-02 00:00:00', TIMESTAMP '2026-08-01 10:00:00')
                """, UUID.randomUUID(), supplementBid, FRENCH, SENDER);
        // 7. Partage admin : la part de l'expéditeur n'est pas un remboursement à déduire.
        splitPayment = payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "80.00", "4.00",
                "2026-10-10T10:00:00Z", "40.00");
        jdbc.update("INSERT INTO payment_splits (payment_id, sender_refund_amount, traveler_payout_amount, currency, "
                + "mode, status) VALUES (?, 40.00, 36.00, 'EUR', 'REFUND_TRANSFER', 'COMPLETED')", splitPayment);
        // 8. Fil de négociation payé par carte (paiement rattaché au fil, sans bid).
        UUID thread = UUID.randomUUID();
        withoutForeignKeys("INSERT INTO negotiation_threads (id, package_request_id, traveler_id, "
                + "traveler_travel_date, traveler_available_kg, current_price_eur, currency) VALUES ('" + thread
                + "', '" + UUID.randomUUID() + "', '" + FRENCH + "', DATE '2026-11-12', 10, 60.00, 'EUR')");
        payment(null, thread, "STRIPE", "EUR", "60.00", "3.00", "2026-11-11T10:00:00Z", null);
        // 9. Carte versée en décembre, remboursée en janvier suivant.
        refundedNextYear = payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "40.00", "2.00",
                "2026-12-20T10:00:00Z", "20.00");
        refundAudit(refundedNextYear, "2027-01-05T10:00:00Z");

        // Hors calcul.
        payment(bid(tripCi, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "100.00", "5.00",
                "2026-05-01T10:00:00Z", null);
        UUID inTransit = bid(tripEur, "IN_TRANSIT", "CASH", "EUR");
        jdbc.update("UPDATE bids SET weight_kg = 2.00, commission_status = 'CHARGED' WHERE id = ?", inTransit);
        payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "70.00", "3.50",
                "2025-12-31T23:59:59Z", null);
        UUID deleted = payment(bid(tripEur, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "999.00", "9.00",
                "2026-05-05T10:00:00Z", null);
        jdbc.update("UPDATE payments SET deleted_at = now() WHERE id = ?", deleted);

        // Professionnel : déclaré au titre du pays de l'entreprise.
        payment(bid(tripPro, "COMPLETED", "STRIPE", "EUR"), null, "STRIPE", "EUR", "30.00", "1.50",
                "2026-07-01T10:00:00Z", null);

        Dac7ReportReadModel readModel = new Dac7ReportReadModel(new NamedParameterJdbcTemplate(dataSource));
        Dac7ReportService service = new Dac7ReportService(readModel,
                new Dac7ResidencyPolicy(new Dac7Properties(false, null)), new FixedParityExchangeRateProvider());
        report = service.annualReport(2026);
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static void user(UUID id, String country) {
        jdbc.update("INSERT INTO users (id, firebase_uid, username, country) VALUES (?, ?, ?, ?)",
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
                """, id, traveler, new java.math.BigDecimal(pricePerKg), currency);
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

    private static UUID payment(UUID bidId, UUID threadId, String rail, String currency, String amount,
                                String commission, String releasedAt, String refunded) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payments (id, bid_id, negotiation_thread_id, stripe_payment_intent_id, rail, amount,
                  currency, commission_amount, refunded_amount, status, escrow_released_at, legacy_destination_charge,
                  disputed, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'RELEASED', CAST(? AS TIMESTAMPTZ), false, false, now(), now())
                """, id, bidId, threadId, "pi_" + id, rail, new java.math.BigDecimal(amount), currency,
                new java.math.BigDecimal(commission), refunded == null ? null : new java.math.BigDecimal(refunded),
                releasedAt);
        return id;
    }

    private static void refundAudit(UUID paymentId, String at) {
        jdbc.update("INSERT INTO audit_log (entity_type, entity_id, action, payload, created_at) "
                + "VALUES ('PAYMENT', ?, 'REFUND_AFTER_RELEASE', '{}'::jsonb, CAST(? AS TIMESTAMPTZ))", paymentId, at);
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

    private static CurrencyAmounts amounts(QuarterReport quarter, String currency) {
        return quarter.byCurrency().stream().filter(c -> c.currency().equals(currency)).findFirst().orElseThrow();
    }

    // ── Assertions ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("seuls les résidents déclarables figurent ; l'ivoirien est justifié à part")
    void perimetre() {
        assertThat(report.travelers()).extracting(TravelerReport::travelerId).containsExactlyInAnyOrder(FRENCH, PRO);
        assertThat(report.nonDeclarableTravelers()).containsExactly(IVORIAN);
        assertThat(report.unknownResidenceTravelers()).isEmpty();
        assertThat(traveler(FRENCH).taxInfoProvided()).isTrue();
        assertThat(traveler(PRO).taxInfoProvided()).isFalse();
    }

    @Test
    @DisplayName("professionnel : pays de l'entreprise (BE), une seule opération suffit")
    void professionnel() {
        TravelerReport pro = traveler(PRO);
        assertThat(pro.residenceCountry()).isEqualTo("BE");
        assertThat(pro.quarters().get(2).operations()).isEqualTo(1);
        assertThat(pro.quarters().get(2).netEur()).isEqualByComparingTo("28.50");
        assertThat(pro.quarters().get(2).commissionEur()).isEqualByComparingTo("1.50");
    }

    @Test
    @DisplayName("T1 : la dernière seconde du 31/03 UTC reste au premier trimestre ; 2025 exclu")
    void premierTrimestre() {
        QuarterReport q1 = traveler(FRENCH).quarters().get(0);
        assertThat(q1.operations()).isEqualTo(1);
        assertThat(q1.netEur()).isEqualByComparingTo("95.00");
        assertThat(q1.commissionEur()).isEqualByComparingTo("5.00");
    }

    @Test
    @DisplayName("T2 : mobile money XOF du 01/04 00:00, espèces (poids, grille, net négocié), remboursement déduit")
    void deuxiemeTrimestre() {
        QuarterReport q2 = traveler(FRENCH).quarters().get(1);
        // XOF 65 595,70 net ; espèces 35 + 20 ; carte 47,50 moins 10 remboursés.
        assertThat(q2.operations()).isEqualTo(4);
        assertThat(amounts(q2, "XOF").net()).isEqualByComparingTo("65595.70");
        assertThat(amounts(q2, "XOF").commission()).isEqualByComparingTo("3279.79");
        assertThat(amounts(q2, "EUR").net()).isEqualByComparingTo("92.50");
        assertThat(amounts(q2, "EUR").commission()).isEqualByComparingTo("4.25");
        assertThat(amounts(q2, "EUR").refundsDeducted()).isEqualByComparingTo("10.00");
        assertThat(amounts(q2, "EUR").operations()).isEqualTo(3);
        assertThat(q2.netEur()).isEqualByComparingTo("192.50");
        assertThat(q2.commissionEur()).isEqualByComparingTo("9.25");
        assertThat(q2.refundsDeductedEur()).isEqualByComparingTo("10.00");
        assertThat(q2.eurComplete()).isTrue();
    }

    @Test
    @DisplayName("espèces sans commission enregistrée : comptée zéro et signalée")
    void commissionEspecesInconnue() {
        assertThat(report.anomalies()).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo(AnomalyType.COMMISSION_UNKNOWN);
            assertThat(a.sourceId()).isEqualTo(cashWithoutCommission);
        });
    }

    @Test
    @DisplayName("T3 : un supplément ajoute au montant sans compter une opération")
    void troisiemeTrimestre() {
        QuarterReport q3 = traveler(FRENCH).quarters().get(2);
        assertThat(q3.operations()).isZero();
        assertThat(q3.netEur()).isEqualByComparingTo("9.50");
        assertThat(q3.commissionEur()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("T4 : partage admin (part du voyageur), fil de négociation, remboursement de janvier non déduit")
    void quatriemeTrimestre() {
        QuarterReport q4 = traveler(FRENCH).quarters().get(3);
        assertThat(q4.operations()).isEqualTo(3);
        // 36 (partage) + 57 (fil) + 38 (décembre, remboursé en 2027).
        assertThat(q4.netEur()).isEqualByComparingTo("131.00");
        assertThat(q4.commissionEur()).isEqualByComparingTo("9.00");
        assertThat(q4.refundsDeductedEur()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("remboursement de janvier 2027 : listé à part pour une rectification ; le partage n'y est pas")
    void remboursementAnneeSuivante() {
        assertThat(report.outOfYearRefunds()).singleElement().satisfies(r -> {
            assertThat(r.sourceId()).isEqualTo(refundedNextYear);
            assertThat(r.travelerId()).isEqualTo(FRENCH);
            assertThat(r.amount()).isEqualByComparingTo("20.00");
            assertThat(r.operationYear()).isEqualTo(2026);
            assertThat(r.refundYear()).isEqualTo(2027);
            assertThat(r.amountEur()).isEqualByComparingTo("20.00");
        });
        assertThat(report.anomalies()).noneMatch(a -> a.type() == AnomalyType.REFUND_DATE_ESTIMATED);
        assertThat(report.anomalies()).noneMatch(a -> splitPayment.equals(a.sourceId()));
    }

    @Test
    @DisplayName("devise flottante sans taux historique : jamais devinée, signalée")
    void deviseFlottanteSansTaux() {
        UUID trip = trip(FRENCH, "USD", "10.00");
        UUID usd = payment(bid(trip, "COMPLETED", "STRIPE", "USD"), null, "STRIPE", "USD", "100.00", "5.00",
                "2027-03-01T10:00:00Z", null);
        Dac7Report next = new Dac7ReportService(
                new Dac7ReportReadModel(new NamedParameterJdbcTemplate(postgres.getPostgresDatabase())),
                new Dac7ResidencyPolicy(new Dac7Properties(false, null)), new FixedParityExchangeRateProvider())
                .annualReport(2027);
        QuarterReport q1 = next.travelers().get(0).quarters().get(0);
        assertThat(q1.eurComplete()).isFalse();
        assertThat(amounts(q1, "USD").net()).isEqualByComparingTo("95.00");
        assertThat(next.anomalies()).anySatisfy(a -> {
            assertThat(a.type()).isEqualTo(AnomalyType.EXCHANGE_RATE_MISSING);
            assertThat(a.sourceId()).isEqualTo(usd);
        });
        // Le remboursement de janvier d'une opération de 2026 apparaît aussi dans l'année du remboursement.
        assertThat(next.outOfYearRefunds()).extracting(Dac7Report.OutOfYearRefund::sourceId)
                .containsExactly(refundedNextYear);
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
        assertThat(report.toString()).doesNotContain("chiffre").doesNotContain("Diallo Express");
        List<String> columns = List.of("first_name", "last_name", "birth_date", "residence_street");
        assertThat(columns).allSatisfy(c -> assertThat(report.toString()).doesNotContain(c));
    }
}
