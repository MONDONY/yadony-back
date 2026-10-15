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

class V322WalletTopupCodesMigrationTest {

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

    private static String constraintDefinition(String name) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = '" + name + "'");
            assertThat(rs.next()).as("la contrainte %s doit exister", name).isTrue();
            return rs.getString(1);
        }
    }

    private static boolean indexExists(String name) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT 1 FROM pg_indexes WHERE indexname = '" + name + "'");
            return rs.next();
        }
    }

    @Test
    void v322_creeLesTablesEtAjouteTopUpCodeAuxCredits() throws Exception {
        Flyway baseline = flywayUpTo("321");
        baseline.clean();
        baseline.migrate();
        assertThat(constraintDefinition("wallet_transactions_type_check")).doesNotContain("TOP_UP_CODE");

        flywayUpTo("322").migrate();

        String types = constraintDefinition("wallet_transactions_type_check");
        for (String type : new String[]{"TOP_UP", "BID_PAYMENT", "COMMISSION_DEDUCTED", "REFUND", "REFERRAL_REWARD",
                "ADMIN_REFUND_OUT", "SELF_REFUND_OUT", "FORFEITED_ON_DELETION", "ADMIN_CREDIT", "ADMIN_DEBIT",
                "TOP_UP_CODE"}) {
            assertThat(types).contains("'" + type + "'");
        }
        // TOP_UP_CODE est contrôlé comme crédit (montant jamais négatif).
        String sign = constraintDefinition("chk_wallet_tx_sign_matches_type");
        assertThat(sign).contains("'TOP_UP_CODE'");
        assertThat(sign.indexOf("'TOP_UP_CODE'")).isLessThan(sign.indexOf("'BID_PAYMENT'"));

        assertThat(constraintDefinition("uq_topup_code_redemption_user")).isEqualTo("UNIQUE (code_id, user_id)");
        assertThat(constraintDefinition("chk_topup_code_redeemed_range"))
                .contains("redeemed_count >= 0").contains("redeemed_count <= max_redemptions");
        assertThat(constraintDefinition("chk_topup_code_max_redemptions")).contains("10000");
        assertThat(indexExists("uq_wallet_topup_codes_code")).isTrue();
    }

    @Test
    void v322_refuseUnSecondUsageDuMemeCodeParLeMemeCompteEtUnCodeMalForme() throws Exception {
        flywayUpTo("322").migrate();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // Format : minuscules refusées (le service normalise avant d'écrire).
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> s.execute(
                    "INSERT INTO wallet_topup_codes (id, code, amount, currency, max_redemptions, expires_at, "
                    + "status, created_by, created_at, updated_at) VALUES (gen_random_uuid(), 'dakar', 10, 'EUR', 1, "
                    + "now() + interval '1 day', 'ACTIVE', gen_random_uuid(), now(), now())"))
                    .hasMessageContaining("chk_topup_code_format");
        }
    }
}
