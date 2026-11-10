package com.yadony.api.dac7;

import com.yadony.api.auth.TaxInfoRetentionPort.Retention;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.common.AuditService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Conservation et purge du NIF des comptes supprimés, sur PostgreSQL avec la chaîne Flyway complète. Date du jour
 * figée au 10/11/2026 : fenêtre de conservation par défaut depuis le 01/01/2025.
 */
@DisplayName("DAC7 : conservation à la suppression et purge mensuelle des NIF")
class Dac7TaxInfoPurgePostgresIT {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T10:00:00Z"), ZoneOffset.UTC);

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static NamedParameterJdbcTemplate named;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        named = new NamedParameterJdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Dac7TaxInfoRetention retention(boolean enabled, Dac7ReportReadModel readModel) {
        Dac7Properties properties = new Dac7Properties(enabled, null);
        return new Dac7TaxInfoRetention(new Dac7ResidencyPolicy(properties), readModel, properties, CLOCK,
                transactionManager);
    }

    /** Compte (supprimé ou non) avec NIF gardé et résidence figée, payé une fois à la date donnée. */
    private static UUID account(boolean deleted, String frozenResidence, String paidAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, firebase_uid, username, country) VALUES (?, ?, ?, 'SN')",
                id, "uid-purge-" + id, "purge_" + id.toString().substring(0, 8));
        jdbc.update("UPDATE users SET tax_identification_number = 'chiffre', tax_country = 'chiffre', "
                + "vat_number = 'chiffre', tax_info_provided_at = now(), tax_residence_country = ?, "
                + "tax_seller_status = 'INDIVIDUAL', deleted_at = CASE WHEN ? THEN now() END WHERE id = ?",
                frozenResidence, deleted, id);
        UUID trip = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  available_kg, price_per_kg, transport_mode, total_kg, pickup_address_label, pickup_lat, pickup_lng,
                  delivery_address_label, delivery_lat, delivery_lng, currency, created_at, updated_at)
                VALUES (?, ?, 'Paris', 'Dakar', DATE '2024-03-01', 20.00, 10.00, 'PLANE', 23.00,
                  'Gare du Nord', 48.88, 2.35, 'Plateau', 14.69, -17.44, 'EUR', now(), now())
                """, trip, id);
        UUID bid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description, recipient_name,
                  recipient_phone, status, payment_method, currency, created_at, updated_at)
                VALUES (?, ?, ?, 1.00, 'Vêtements', 'Aminata', '+221701234567', 'COMPLETED', 'STRIPE', 'EUR',
                  now(), now())
                """, bid, trip, id);
        jdbc.update("""
                INSERT INTO payments (id, bid_id, stripe_payment_intent_id, rail, amount, currency, commission_amount,
                  status, escrow_released_at, legacy_destination_charge, disputed, created_at, updated_at)
                VALUES (?, ?, ?, 'STRIPE', 50.00, 'EUR', 2.50, 'RELEASED', CAST(? AS TIMESTAMPTZ), false, false,
                  now(), now())
                """, UUID.randomUUID(), bid, "pi_" + UUID.randomUUID(), paidAt);
        return id;
    }

    private static Map<String, Object> taxColumns(UUID id) {
        return jdbc.queryForMap("SELECT tax_identification_number, tax_country, vat_number, tax_info_provided_at, "
                + "tax_residence_country, tax_seller_status FROM users WHERE id = ?", id);
    }

    @Test
    @DisplayName("purge : gardé dans la fenêtre, purgé hors fenêtre ou hors Union, actifs intacts, idempotente, "
            + "une erreur n'arrête pas les autres")
    void purge() {
        UUID inWindow = account(true, "FR", "2025-06-01T10:00:00Z");
        UUID outOfWindow = account(true, "FR", "2024-06-01T10:00:00Z");
        UUID notDeclarable = account(true, "SN", "2026-06-01T10:00:00Z");
        UUID failing = account(true, "FR", "2024-06-01T10:00:00Z");
        UUID active = account(false, "FR", "2024-06-01T10:00:00Z");
        Dac7ReportReadModel readModel = spy(new Dac7ReportReadModel(named));
        doThrow(new IllegalStateException("panne")).when(readModel).hasBeenPaidSince(eq(failing), any());
        AuditService audit = mock(AuditService.class);
        Dac7TaxInfoPurgeScheduler scheduler = new Dac7TaxInfoPurgeScheduler(named, retention(false, readModel), audit,
                transactionManager);

        Dac7TaxInfoPurgeScheduler.PurgeOutcome first = scheduler.purge();

        assertThat(first.purged()).isGreaterThanOrEqualTo(2);
        assertThat(first.failed()).isGreaterThanOrEqualTo(1);
        assertThat(taxColumns(inWindow).get("tax_identification_number")).isEqualTo("chiffre");
        assertThat(taxColumns(inWindow).get("tax_residence_country")).isEqualTo("FR");
        assertThat(taxColumns(outOfWindow).values()).allMatch(v -> v == null);
        assertThat(taxColumns(notDeclarable).values()).allMatch(v -> v == null);
        assertThat(taxColumns(failing).get("tax_identification_number")).isEqualTo("chiffre");
        assertThat(taxColumns(active).get("tax_identification_number")).isEqualTo("chiffre");
        verify(audit).log(eq("USER"), eq(outOfWindow), eq("DAC7_TAX_INFO_PURGED"), isNull(),
                eq(Map.of("retentionYears", 2)));
        verify(audit).log(eq("USER"), eq(notDeclarable), eq("DAC7_TAX_INFO_PURGED"), isNull(), anyMap());
        verify(audit, never()).log(any(), eq(inWindow), any(), any(), anyMap());

        // Idempotente : rien de plus à purger, aucun audit de plus.
        scheduler.purge();
        verify(audit, times(1)).log(eq("USER"), eq(outOfWindow), eq("DAC7_TAX_INFO_PURGED"), isNull(), anyMap());
        verify(audit, times(1)).log(eq("USER"), eq(notDeclarable), eq("DAC7_TAX_INFO_PURGED"), isNull(), anyMap());
    }

    @Test
    @DisplayName("conservation : la lecture en échec vit dans sa propre transaction, celle de la suppression continue")
    void lectureEnEchec_transactionDeSuppressionIntacte() {
        UUID id = account(false, null, "2026-03-01T10:00:00Z");
        // Lecture qui échoue VRAIMENT sur PostgreSQL (division par zéro) : sans transaction séparée, celle de la
        // suppression passerait en « current transaction is aborted ».
        Dac7ReportReadModel failing = new Dac7ReportReadModel(named) {
            @Override
            public boolean hasBeenPaidSince(UUID travelerId, Instant since) {
                return Boolean.TRUE.equals(jdbc.queryForObject("SELECT 1 / 0 = 1", Boolean.class));
            }
        };
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", id);
        user.setCountry("FR");
        user.setTaxIdentificationNumber("3023217600053");

        Optional<Retention> decision = new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.update("UPDATE users SET first_name = 'Utilisateur' WHERE id = ?", id);
            Optional<Retention> d = retention(true, failing).retention(user);
            jdbc.update("UPDATE users SET last_name = 'supprimé', deleted_at = now() WHERE id = ?", id);
            return d;
        });

        assertThat(decision).contains(new Retention("FR", null));
        assertThat(jdbc.queryForObject("SELECT first_name || ' ' || last_name FROM users WHERE id = ?", String.class,
                id)).isEqualTo("Utilisateur supprimé");
    }

    @Test
    @DisplayName("conservation sur le vrai schéma : payé dans la fenêtre gardé, avant la fenêtre effacé, éteint rien")
    void conservationSurLeSchema() {
        UUID recent = account(false, null, "2025-02-01T10:00:00Z");
        UUID old = account(false, null, "2024-12-31T23:59:59Z");
        Dac7ReportReadModel readModel = new Dac7ReportReadModel(named);
        assertThat(retention(true, readModel).retention(user(recent))).isPresent();
        assertThat(retention(true, readModel).retention(user(old))).isEmpty();
        assertThat(retention(false, readModel).retention(user(recent))).isEmpty();
    }

    private static UserEntity user(UUID id) {
        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", id);
        user.setCountry("FR");
        user.setTaxIdentificationNumber("x");
        return user;
    }
}
