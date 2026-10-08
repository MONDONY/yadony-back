package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V300 (FLUTTER-E2) : colonnes de la procédure « destinataire absent » et table
 * {@code payment_splits} du partage admin, appliquées sur un vrai PostgreSQL.
 */
class V300DestinataireAbsentMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

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

    private static String columnType(String table, String column) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT data_type FROM information_schema.columns WHERE table_name = '"
                    + table + "' AND column_name = '" + column + "'");
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void v300_ajouteLaProcedureEtLaTableDePartage() throws Exception {
        Flyway baseline = flywayUpTo("295");
        baseline.clean();
        baseline.migrate();
        assertThat(columnType("cancellations", "hold_until")).isNull();

        flywayUpTo("300").migrate();

        assertThat(columnType("bids", "arrived_at")).startsWith("timestamp");
        assertThat(columnType("conversations", "traveler_last_message_at")).startsWith("timestamp");
        for (String col : new String[]{"contact_confirmed_at", "hold_until", "retry_appointment_at", "unclaimed_at"}) {
            assertThat(columnType("cancellations", col)).isEqualTo("timestamp with time zone");
        }
        assertThat(columnType("disputes", "sender_refund_amount")).isEqualTo("numeric");
        assertThat(columnType("payment_splits", "status")).isEqualTo("character varying");

        // Contraintes vérifiées sur des copies sans FK (aucune dépendance aux données métier).
        exec("CREATE TABLE splits_probe (LIKE payment_splits INCLUDING ALL)");
        String insert = "INSERT INTO splits_probe (payment_id, sender_refund_amount, traveler_payout_amount, currency, mode, status) VALUES ";
        exec(insert + "('00000000-0000-0000-0000-000000000001', 10, 20, 'EUR', 'REFUND_TRANSFER', 'CLAIMED')");
        assertThatThrownBy(() -> exec(insert + "('00000000-0000-0000-0000-000000000001', 1, 1, 'EUR', 'REFUND_TRANSFER', 'CLAIMED')"))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> exec(insert + "('00000000-0000-0000-0000-000000000002', 0, 0, 'EUR', 'REFUND_TRANSFER', 'CLAIMED')"))
                .hasMessageContaining("chk_payment_splits_amounts");
        assertThatThrownBy(() -> exec(insert + "('00000000-0000-0000-0000-000000000003', 1, 0, 'EUR', 'REFUND_TRANSFER', 'FAILED')"))
                .hasMessageContaining("chk_payment_splits_status");

        exec("CREATE TABLE cancellations_probe (LIKE cancellations INCLUDING CONSTRAINTS)");
        exec("ALTER TABLE cancellations_probe ALTER COLUMN bid_id DROP NOT NULL, ALTER COLUMN cancelled_by DROP NOT NULL");
        String c = "INSERT INTO cancellations_probe (id, reason, refund_status, rematch_status, no_show_status, scope, created_at, updated_at, ";
        assertThatThrownBy(() -> exec(c + "unclaimed_at) VALUES (gen_random_uuid(), 'RECIPIENT_NO_SHOW', 'PENDING', 'NONE', 'CONFIRMED', 'DELIVERY', now(), now(), now())"))
                .hasMessageContaining("cancellations_unclaimed_requires_hold_check");
        assertThatThrownBy(() -> exec(c + "contact_proof) VALUES (gen_random_uuid(), 'RECIPIENT_NO_SHOW', 'PENDING', 'NONE', 'CONFIRMED', 'DELIVERY', now(), now(), 'SMS')"))
                .hasMessageContaining("cancellations_contact_proof_check");
        exec(c + "hold_until, unclaimed_at, contact_proof) VALUES (gen_random_uuid(), 'RECIPIENT_NO_SHOW', 'PENDING', 'NONE', 'CONFIRMED', 'DELIVERY', now(), now(), now(), now(), 'CALL')");

        exec("CREATE TABLE disputes_probe (LIKE disputes INCLUDING CONSTRAINTS)");
        assertThatThrownBy(() -> exec("INSERT INTO disputes_probe (id, status, refund_frozen, created_at, updated_at, sender_refund_amount) "
                + "VALUES (gen_random_uuid(), 'OPEN', false, now(), now(), 10)"))
                .hasMessageContaining("disputes_split_amounts_check");
        exec("DROP TABLE splits_probe, cancellations_probe, disputes_probe");
    }
}
