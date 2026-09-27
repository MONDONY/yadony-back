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
 * V266 : correction manuelle de solde wallet par un admin. Le CHECK des types de
 * transaction reprend exactement la liste de V258 et y ajoute ADMIN_CREDIT et ADMIN_DEBIT ;
 * deux colonnes nullables portent le motif interne et l'admin auteur.
 */
class V266WalletAdminAdjustmentMigrationTest {

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

    private static String columnInfo(String table, String column) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT data_type, is_nullable, character_maximum_length "
                    + "FROM information_schema.columns WHERE table_name = '" + table
                    + "' AND column_name = '" + column + "'");
            return rs.next() ? rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) : null;
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
    void v266_etendLesTypesEtAjouteLesColonnesAdmin() throws Exception {
        Flyway baseline = flywayUpTo("265");
        baseline.clean();
        baseline.migrate();
        assertThat(columnInfo("wallet_transactions", "admin_reason")).isNull();
        assertThat(columnInfo("wallet_transactions", "admin_actor_id")).isNull();
        assertThat(constraintDefinition("wallet_transactions_type_check")).doesNotContain("ADMIN_CREDIT");

        flywayUpTo("266").migrate();

        assertThat(columnInfo("wallet_transactions", "admin_reason")).isEqualTo("character varying|YES|500");
        assertThat(columnInfo("wallet_transactions", "admin_actor_id")).isEqualTo("uuid|YES|null");
        String types = constraintDefinition("wallet_transactions_type_check");
        for (String type : new String[]{"TOP_UP", "BID_PAYMENT", "COMMISSION_DEDUCTED", "REFUND", "REFERRAL_REWARD",
                "ADMIN_REFUND_OUT", "SELF_REFUND_OUT", "FORFEITED_ON_DELETION", "ADMIN_CREDIT", "ADMIN_DEBIT"}) {
            assertThat(types).contains("'" + type + "'");
        }
    }
}
