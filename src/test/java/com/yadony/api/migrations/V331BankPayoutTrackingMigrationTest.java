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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V331BankPayoutTrackingMigrationTest {

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

    @Test
    void v331_createsTheBankPayoutTrackingTable() throws Exception {
        Flyway baseline = flywayUpTo("330");
        baseline.clean();
        baseline.migrate();
        flywayUpTo("331").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = 'bank_payout_tracking' ORDER BY column_name");
            List<String> columns = new ArrayList<>();
            while (rs.next()) columns.add(rs.getString(1));
            assertThat(columns).containsExactly("arrival_date", "available_on", "connected_account_id", "created_at",
                    "deleted_at", "destination_payment_id", "failure_code", "id", "notified_key", "paid_at",
                    "payment_id", "payout_event_at", "payout_id", "payout_status", "transfer_id", "traveler_id",
                    "updated_at");

            for (String index : List.of("idx_bank_payout_tracking_destination_payment",
                    "idx_bank_payout_tracking_payout", "idx_bank_payout_tracking_account")) {
                rs = s.executeQuery("SELECT 1 FROM pg_indexes WHERE indexname = '" + index + "'");
                assertThat(rs.next()).as(index).isTrue();
            }

            UUID orphan = UUID.randomUUID();
            assertThatThrownBy(() -> s.execute("INSERT INTO bank_payout_tracking (id, created_at, updated_at, "
                    + "payment_id, transfer_id, connected_account_id) VALUES ('" + UUID.randomUUID()
                    + "', now(), now(), '" + orphan + "', 'tr', 'acct')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("fk_bank_payout_tracking_payment");

            rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = 'chk_bank_payout_tracking_payout_status'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("in_transit").contains("canceled");
            rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = 'uq_bank_payout_tracking_payment'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("UNIQUE (payment_id)");
        }
    }
}
