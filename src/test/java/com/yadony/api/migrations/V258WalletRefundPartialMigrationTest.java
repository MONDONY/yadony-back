package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V258 : remboursement partiel du wallet et solde non-cash perdu à la suppression.
 * Colonnes additives (parent_request_id, failure_reason) et CHECK des types de
 * transaction étendu à FORFEITED_ON_DELETION. Même méthode que V256 : PostgreSQL
 * embarqué migré jusqu'à V257 puis V258, lecture du schéma via information_schema
 * et pg_get_constraintdef.
 */
class V258WalletRefundPartialMigrationTest {

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

    private static boolean columnExists(String table, String column) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT 1 FROM information_schema.columns WHERE table_name = '"
                    + table + "' AND column_name = '" + column + "'");
            return rs.next();
        }
    }

    private static String constraintDefinition(String name) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = '" + name + "'");
            assertThat(rs.next()).as("la contrainte %s doit exister", name).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void v258_ajouteColonnesEtEtendLesTypes() throws Exception {
        flywayUpTo("257").migrate();
        assertThat(columnExists("wallet_refund_requests", "parent_request_id")).isFalse();
        assertThat(columnExists("wallet_refund_request_items", "failure_reason")).isFalse();
        assertThat(constraintDefinition("wallet_transactions_type_check"))
                .doesNotContain("FORFEITED_ON_DELETION");

        flywayUpTo("258").migrate();

        assertThat(columnExists("wallet_refund_requests", "parent_request_id")).isTrue();
        assertThat(columnExists("wallet_refund_request_items", "failure_reason")).isTrue();
        String types = constraintDefinition("wallet_transactions_type_check");
        assertThat(types).contains("FORFEITED_ON_DELETION")
                .contains("TOP_UP").contains("SELF_REFUND_OUT").contains("ADMIN_REFUND_OUT")
                .contains("REFERRAL_REWARD").contains("REFUND").contains("BID_PAYMENT")
                .contains("COMMISSION_DEDUCTED");
        // Les gardes « montant > 0 » restent : un remboursement partiel est toujours strictement positif.
        assertThat(constraintDefinition("chk_wallet_refund_requests_amount_positive")).contains("amount > ");
        assertThat(constraintDefinition("chk_wallet_refund_request_items_amount_positive")).contains("amount > ");
    }
}
