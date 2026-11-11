package com.yadony.api.dac7;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.dac7.Dac7DeclarationService.Declaration;
import com.yadony.api.dac7.Dac7DeclarationService.Seller;
import com.yadony.api.dac7.export.Dac7XsdValidator;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lot 3b sur PostgreSQL, chaîne Flyway complète et vraies lignes : calcul du lot 3a, taux BCE lus dans
 * {@code ecb_exchange_rates}, identité, blocages, export XML validé contre le XSD de la DGFiP, et les requêtes des
 * rappels et des récapitulatifs. Aucun appel réseau : téléchargement BCE éteint, prestataire KYC simulé.
 *
 * <ul>
 *   <li>{@code COMPLETE} : résidente française, NIF fourni, identité complète, carte EUR au T1, carte USD au T2 avec
 *       un taux BCE du jour, espèces EUR au T4 ;</li>
 *   <li>{@code USD_NO_RATE} : résident français, NIF fourni, carte USD au T3 sans taux connu ;</li>
 *   <li>{@code NO_TAX} : résident français payé, sans NIF (à solliciter) ;</li>
 *   <li>{@code IVORIAN} : payé, hors périmètre.</li>
 * </ul>
 */
@DisplayName("DAC7 lot 3b : déclaration et export sur la chaîne Flyway complète")
class Dac7ExportPostgresIT {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate named;
    private static final UUID COMPLETE = UUID.randomUUID();
    private static final UUID USD_NO_RATE = UUID.randomUUID();
    private static final UUID NO_TAX = UUID.randomUUID();
    private static final UUID IVORIAN = UUID.randomUUID();
    /** Sans NIF, sans date de naissance, USD sans taux : trois raisons d'exclusion cumulées. */
    private static final UUID MULTI = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final String NIF = "1234567890123";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-01-15T09:00:00Z"), ZoneOffset.UTC);
    private static final Map<UUID, UserEntity> ENTITIES = new java.util.HashMap<>();

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
        named = new NamedParameterJdbcTemplate(dataSource);

        user(COMPLETE, "FR", true);
        user(USD_NO_RATE, "FR", true);
        user(NO_TAX, "FR", false);
        user(IVORIAN, "CI", false);
        user(SENDER, "FR", false);
        user(MULTI, "FR", false);
        jdbc.update("UPDATE users SET birth_date = NULL WHERE id = ?", MULTI);
        ENTITIES.get(MULTI).setBirthDate(null);

        payment(trip(COMPLETE, "EUR"), "EUR", "100.00", "5.00", "2026-02-10T10:00:00Z");
        payment(trip(COMPLETE, "USD"), "USD", "108.00", "5.40", "2026-05-04T10:00:00Z");
        cash(trip(COMPLETE, "EUR"), "2026-11-02 10:00:00");
        payment(trip(USD_NO_RATE, "USD"), "USD", "50.00", "2.50", "2026-08-03T10:00:00Z");
        payment(trip(NO_TAX, "EUR"), "EUR", "40.00", "2.00", "2026-03-01T10:00:00Z");
        payment(trip(IVORIAN, "EUR"), "EUR", "70.00", "3.50", "2026-03-01T10:00:00Z");
        payment(trip(MULTI, "USD"), "USD", "30.00", "1.50", "2026-08-04T10:00:00Z");
        // Samedi 09/05/2026 : jour non publié par la BCE, taux du vendredi 08/05 (RATE_DATE_ESTIMATED).
        payment(trip(NO_TAX, "USD"), "USD", "21.80", "1.09", "2026-05-09T10:00:00Z");
        // Taux BCE du 04/05/2026 : 1,08 USD pour 1 € ; aucun taux autour du 03/08/2026.
        jdbc.update("INSERT INTO ecb_exchange_rates (currency, rate_date, units_per_eur) VALUES "
                + "('USD', DATE '2026-05-04', 1.08), ('USD', DATE '2026-05-05', 1.09), ('USD', DATE '2026-05-08', 1.09)");
        jdbc.update("INSERT INTO admin_users (id, firebase_uid, email, role, status, must_change_password, "
                + "created_at, updated_at) VALUES (?, 'uid-admin-dac7', 'equipe@yadony.test', 'ADMIN', 'ACTIVE', "
                + "false, now(), now())", ADMIN);
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    // ── Services réels, dépôt JPA simulé par les entités équivalentes aux lignes ─────────────────────────────────

    private static Dac7ExportProperties operator() {
        return new Dac7ExportProperties(null, "123456789", "Yadony SAS", null, null, "10 rue de la Paix", "75002",
                "Paris", true);
    }

    private static UserRepository repository() {
        UserRepository users = mock(UserRepository.class);
        when(users.findAllById(any())).thenAnswer(inv -> {
            List<UserEntity> found = new ArrayList<>();
            for (UUID id : (Collection<UUID>) inv.getArgument(0)) {
                if (ENTITIES.containsKey(id)) found.add(ENTITIES.get(id));
            }
            return found;
        });
        when(users.findById(any())).thenAnswer(inv -> Optional.ofNullable(ENTITIES.get((UUID) inv.getArgument(0))));
        return users;
    }

    private static Dac7DeclarationService declarations() {
        Dac7Properties properties = new Dac7Properties(true, null, 2);
        Dac7ResidencyPolicy residency = new Dac7ResidencyPolicy(properties);
        Dac7ReportReadModel readModel = new Dac7ReportReadModel(named);
        EcbExchangeRateProvider rates = new EcbExchangeRateProvider(new FixedParityExchangeRateProvider(), named,
                new EcbHistoricalRatesClient(RestClient.create(), new Dac7EcbProperties(false, null)),
                new Dac7EcbProperties(false, null), CLOCK);
        Dac7VerifiedIdentityPort port = id -> Dac7VerifiedIdentityPort.Lookup.none();
        return new Dac7DeclarationService(new Dac7ReportService(readModel, residency, rates),
                rates, new Dac7IdentityResolver(port), repository(), new Dac7AdminReadModel(named), residency,
                properties, operator(), CLOCK);
    }

    private static Seller seller(Declaration d, UUID id) {
        return d.sellers().stream().filter(s -> s.userId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("quatre trimestres, USD converti au taux BCE du jour, USD sans taux signalé, jamais deviné")
    void quatreTrimestresEtTaux() {
        Declaration d = declarations().declaration(2026, true);

        Seller complete = seller(d, COMPLETE);
        assertThat(complete.report().quarters()).extracting(Dac7Report.QuarterReport::quarter)
                .containsExactly(1, 2, 3, 4);
        assertThat(complete.report().quarters().get(0).netEur()).isEqualByComparingTo("95.00");
        // (108 − 5,40) / 1,08 = 95,00 €.
        assertThat(complete.report().quarters().get(1).netEur()).isEqualByComparingTo("95.00");
        assertThat(complete.report().quarters().get(2).operations()).isZero();
        assertThat(complete.report().quarters().get(3).operations()).isEqualTo(1);
        assertThat(complete.eurComplete()).isTrue();
        assertThat(complete.identityStatus()).isEqualTo(Dac7IdentityStatus.COMPLETE);
        assertThat(complete.exclusions()).isEmpty();

        Seller noRate = seller(d, USD_NO_RATE);
        assertThat(noRate.eurComplete()).isFalse();
        assertThat(noRate.exclusions()).contains(Dac7DeclarationService.EUR_INCOMPLETE);
        assertThat(d.report().anomalies()).anyMatch(a -> a.type() == Dac7Report.AnomalyType.EXCHANGE_RATE_MISSING
                && USD_NO_RATE.equals(a.travelerId()));

        assertThat(seller(d, NO_TAX).exclusions()).containsExactly(Dac7DeclarationService.TAX_INFO_MISSING);
        // Vrai calcul : le versement du samedi prend le taux du vendredi et le signale (non bloquant).
        assertThat(seller(d, NO_TAX).eurComplete()).isTrue();
        assertThat(d.report().anomalies()).anyMatch(a -> a.type() == Dac7Report.AnomalyType.RATE_DATE_ESTIMATED
                && NO_TAX.equals(a.travelerId()) && a.day().equals(java.time.LocalDate.of(2026, 5, 9)));
        assertThat(d.report().anomalies()).noneMatch(a -> a.type() == Dac7Report.AnomalyType.RATE_DATE_ESTIMATED
                && COMPLETE.equals(a.travelerId()));
        assertThat(seller(d, MULTI).exclusions()).containsExactly("IDENTITY_INCOMPLETE", "TAX_INFO_MISSING",
                "EUR_INCOMPLETE");
        assertThat(d.others()).extracting(Dac7DeclarationService.Other::userId).containsExactly(IVORIAN);
        assertThat(d.blockers()).containsExactly("IDENTITY_INCOMPLETE", "TAX_INFO_MISSING", "EUR_INCOMPLETE");
    }

    private static Dac7ExportService exports(AuditService audit) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(named.getJdbcTemplate().getDataSource());
        return new Dac7ExportService(declarations(), repository(), operator(), audit, new Dac7AdminReadModel(named),
                new TransactionTemplate(manager), CLOCK);
    }

    @Test
    @DisplayName("FULL 409 ; PARTIAL valide contre le XSD, raisons cumulées inscrites ; déposé seulement sur confirmation")
    void exportPartielEtDepot() {
        jdbc.update("DELETE FROM dac7_export_sellers");
        jdbc.update("DELETE FROM dac7_export_runs");
        AuditService audit = mock(AuditService.class);
        Dac7ExportService exports = exports(audit);
        Dac7AdminReadModel reads = new Dac7AdminReadModel(named);

        assertThatThrownBy(() -> exports.export(2026, "FULL", null, ADMIN))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> assertThat(e.getProperties())
                        .containsEntry("blockers", List.of("IDENTITY_INCOMPLETE", "TAX_INFO_MISSING", "EUR_INCOMPLETE")));
        // Refusé : rien n'est inscrit, l'ordre 000 reste libre.
        assertThat(reads.nextSequence(2026)).isZero();

        Dac7ExportService.ExportFile file = exports.export(2026, "PARTIAL", null, ADMIN);

        assertThat(file.sellers()).isEqualTo(1);
        assertThat(file.excluded()).isEqualTo(3);
        assertThat(file.sequence()).isZero();
        assertThat(file.fileName()).isEqualTo("DPIDAC7_2026_123456789_000_20270115100000.xml");
        Dac7XsdValidator.validate(file.content());
        String xml = new String(file.content(), StandardCharsets.UTF_8);
        assertThat(xml).contains("<dpi:TIN issuedBy=\"FR\">" + NIF + "</dpi:TIN>")
                .contains("<dpi:ConsQ1 currCode=\"EUR\">95</dpi:ConsQ1>")
                .contains("<dpi:ConsQ2 currCode=\"EUR\">95</dpi:ConsQ2>")
                .contains("<dpi:NumbQ4>1</dpi:NumbQ4>")
                .doesNotContain(USD_NO_RATE.toString())
                .contains("OP_2026_123456789_" + COMPLETE.toString().replace("-", "") + "_000");
        // Trois raisons cumulées tiennent dans la colonne (VARCHAR 255), sans doublon.
        assertThat(jdbc.queryForList("SELECT reason FROM dac7_export_sellers WHERE user_id = ?", String.class, MULTI))
                .containsExactly("IDENTITY_INCOMPLETE,TAX_INFO_MISSING,EUR_INCOMPLETE");
        assertThat(jdbc.queryForList("SELECT status || ':' || COALESCE(reason, doc_ref_id) FROM dac7_export_sellers "
                + "WHERE user_id = ?", String.class, NO_TAX)).containsExactly("EXCLUDED:TAX_INFO_MISSING");
        assertThat(jdbc.queryForObject("SELECT status FROM dac7_export_runs WHERE id = ?", String.class, file.runId()))
                .isEqualTo("GENERATED");
        // Généré mais pas déposé : personne n'est « déjà exporté », le PARTIAL suivant reprend le voyageur (ordre 001).
        assertThat(reads.exportedTravelers(2026)).isEmpty();
        Dac7ExportService.ExportFile second = exports.export(2026, "PARTIAL", null, ADMIN);
        assertThat(second.sellers()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(1);

        // Dépôt confirmé du premier : idempotent, audité une fois.
        exports.markDeposited(file.runId(), ADMIN);
        exports.markDeposited(file.runId(), ADMIN);
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.times(1)).log(eq("DAC7_EXPORT"), eq(file.runId()),
                eq("DAC7_EXPORT_MARKED_DEPOSITED"), eq(ADMIN), any());
        assertThat(reads.exportedTravelers(2026)).containsExactly(COMPLETE);
        assertThatThrownBy(() -> exports.markDeposited(UUID.randomUUID(), ADMIN))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("dac7-export-run-not-found"));

        // Désormais déposé : écarté par défaut, repris sur demande ; un ordre déjà employé est refusé.
        assertThatThrownBy(() -> exports.export(2026, "PARTIAL", null, ADMIN))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> assertThat(e.getProperties())
                        .containsEntry("blockers", List.of("IDENTITY_INCOMPLETE", "TAX_INFO_MISSING", "EUR_INCOMPLETE",
                                "NO_SELLERS")));
        assertThat(exports.export(2026, "PARTIAL", null, true, ADMIN).sequence()).isEqualTo(2);
        assertThatThrownBy(() -> exports.export(2026, "PARTIAL", 1, true, ADMIN))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("dac7-sequence-used"));
    }

    @Test
    @DisplayName("deux exports simultanés de la même année : verrou par année, deux ordres distincts")
    void exportsSimultanes() throws Exception {
        jdbc.update("DELETE FROM dac7_export_sellers");
        jdbc.update("DELETE FROM dac7_export_runs");
        Dac7ExportService exports = exports(mock(AuditService.class));
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> task = () -> {
            start.await();
            return exports.export(2026, "PARTIAL", null, ADMIN).sequence();
        };
        var first = pool.submit(task);
        var second = pool.submit(task);
        start.countDown();
        assertThat(java.util.Set.of(first.get(), second.get())).containsExactlyInAnyOrder(0, 1);
        pool.shutdown();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dac7_export_runs WHERE reporting_year = 2026",
                Integer.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("requêtes des rappels : candidats sans NIF, version groupée de « déjà payé »")
    void requetesDesRappels() {
        Dac7AdminReadModel reads = new Dac7AdminReadModel(named);
        Instant now = CLOCK.instant();
        List<UUID> candidates = reads.reminderCandidates(now.minus(java.time.Duration.ofDays(14)),
                now.minus(java.time.Duration.ofDays(30))).stream().map(Dac7AdminReadModel.ReminderCandidate::userId)
                .toList();
        assertThat(candidates).contains(NO_TAX, IVORIAN, SENDER).doesNotContain(COMPLETE, USD_NO_RATE);
        assertThat(new Dac7ReportReadModel(named).paidTravelers(List.of(NO_TAX, SENDER, IVORIAN, COMPLETE)))
                .containsExactlyInAnyOrder(NO_TAX, IVORIAN, COMPLETE);

        jdbc.update("UPDATE users SET tax_info_requested_at = ?, tax_reminder_count = 1 WHERE id = ?",
                java.sql.Timestamp.from(now.minus(java.time.Duration.ofDays(20))), NO_TAX);
        List<UUID> later = reads.reminderCandidates(now.minus(java.time.Duration.ofDays(14)),
                now.minus(java.time.Duration.ofDays(30))).stream()
                .map(Dac7AdminReadModel.ReminderCandidate::userId).toList();
        // Rappel 1 fait il y a 20 jours de la demande : le rappel 2 n'est dû qu'à 30 jours.
        assertThat(later).doesNotContain(NO_TAX);
        jdbc.update("UPDATE users SET tax_info_requested_at = NULL, tax_reminder_count = 0 WHERE id = ?", NO_TAX);
    }

    @Test
    @DisplayName("registre : dernière démarche, auteur admin par son e-mail ; récapitulatif réservé une seule fois")
    void registreEtRecapitulatif() {
        UUID traveler = UUID.randomUUID();
        jdbc.update("INSERT INTO dac7_due_diligence_log (user_id, action, channel, actor_id, note, created_at) VALUES "
                + "(?, 'REQUEST', 'IN_APP', NULL, NULL, TIMESTAMPTZ '2026-12-01 10:00:00+00'), "
                + "(?, 'TEAM_DECISION', 'ADMIN', ?, 'Appel du voyageur', TIMESTAMPTZ '2026-12-20 10:00:00+00')",
                traveler, traveler, ADMIN);
        Dac7AdminReadModel reads = new Dac7AdminReadModel(named);

        assertThat(reads.lastDiligence(List.of(traveler))).containsEntry(traveler,
                Instant.parse("2026-12-20T10:00:00Z"));
        assertThat(reads.diligence(traveler)).extracting(Dac7AdminReadModel.DiligenceRow::actorName)
                .containsExactly(null, "equipe@yadony.test");
        assertThat(reads.lastOutgoing(traveler)).contains(Instant.parse("2026-12-01T10:00:00Z"));
        assertThat(reads.userExists(COMPLETE)).isTrue();
        assertThat(reads.userExists(UUID.randomUUID())).isFalse();

        assertThat(reads.reserveStatement(traveler, 2026, ADMIN, CLOCK.instant())).isTrue();
        assertThat(reads.reserveStatement(traveler, 2026, ADMIN, CLOCK.instant())).isFalse();
        assertThat(reads.reserveStatement(traveler, 2027, ADMIN, CLOCK.instant())).isTrue();
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private static void user(UUID id, String country, boolean withTax) {
        jdbc.update("INSERT INTO users (id, firebase_uid, username, country, first_name, last_name, birth_date, "
                        + "residence_street, residence_postal_code, city, tax_identification_number) "
                        + "VALUES (?, ?, ?, ?, 'Awa', 'Diallo', DATE '1990-01-02', '1 rue de Paris', '69001', 'Lyon', ?)",
                id, "uid-dac7b-" + id, "dac7b_" + id.toString().substring(0, 8), country, withTax ? "chiffre" : null);
        UserEntity entity = new UserEntity();
        ReflectionTestUtils.setField(entity, "id", id);
        entity.setFirstName("Awa");
        entity.setLastName("Diallo");
        entity.setBirthDate(LocalDate.of(1990, 1, 2));
        entity.setResidenceStreet("1 rue de Paris");
        entity.setResidencePostalCode("69001");
        entity.setCity("Lyon");
        entity.setCountry(country);
        if (withTax) {
            entity.setTaxIdentificationNumber(NIF);
            entity.setTaxCountry("FR");
        }
        ENTITIES.put(id, entity);
    }

    private static UUID trip(UUID traveler, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  available_kg, price_per_kg, transport_mode, total_kg,
                  pickup_address_label, pickup_lat, pickup_lng,
                  delivery_address_label, delivery_lat, delivery_lng, currency, created_at, updated_at)
                VALUES (?, ?, 'Paris', 'Dakar', DATE '2026-03-01', 20.00, 10.00, 'PLANE', 23.00,
                  'Gare du Nord', 48.88, 2.35, 'Plateau', 14.69, -17.44, ?, now(), now())
                """, id, traveler, currency);
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

    private static void payment(UUID trip, String currency, String amount, String commission, String releasedAt) {
        UUID bid = bid(trip, "COMPLETED", "STRIPE", currency);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO payments (id, bid_id, stripe_payment_intent_id, rail, amount, currency, commission_amount,
                  status, escrow_released_at, legacy_destination_charge, disputed, created_at, updated_at)
                VALUES (?, ?, ?, 'STRIPE', ?, ?, ?, 'RELEASED', CAST(? AS TIMESTAMPTZ), false, false, now(), now())
                """, id, bid, "pi_" + id, new BigDecimal(amount), currency, new BigDecimal(commission), releasedAt);
    }

    private static void cash(UUID trip, String deliveredAt) {
        UUID bid = bid(trip, "COMPLETED", "CASH", "EUR");
        jdbc.update("UPDATE bids SET delivered_at = CAST(? AS TIMESTAMP), commission_status = 'CHARGED', "
                + "cash_commission_charged_amount = 0.50 WHERE id = ?", deliveredAt, bid);
    }
}
