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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V309 : verrous en base sur l'argent (lot C de l'audit de cohérence du 05/10). La sonde
 * détecte une incohérence après coup ; ces contraintes empêchent de l'écrire.
 */
class V309MoneyConstraintsMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").load().migrate();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static UUID newUser() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            String suffix = UUID.randomUUID().toString().substring(0, 12);
            ResultSet rs = s.executeQuery("INSERT INTO users (firebase_uid, username) VALUES ('v309-"
                    + suffix + "', 'v309_" + suffix.replace('-', '_') + "') RETURNING id");
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static UUID insertLedger(UUID userId, String type, String amount, String balanceAfter) throws SQLException {
        UUID id = UUID.randomUUID();
        execute("INSERT INTO wallet_transactions (id, user_id, currency, type, amount, balance_after) VALUES ('"
                + id + "', '" + userId + "', 'EUR', '" + type + "', " + amount + ", " + balanceAfter + ")");
        return id;
    }

    private static String constraintDefinition(String name) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '"
                    + name + "'");
            assertThat(rs.next()).as("la contrainte %s doit exister", name).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void unMouvementLaisseUnSoldeNegatif_estRefuse() throws Exception {
        UUID user = newUser();
        assertThatThrownBy(() -> insertLedger(user, "COMMISSION_DEDUCTED", "-5.00", "-5.00"))
                .hasMessageContaining("chk_wallet_tx_balance_after_non_negative");
    }

    @Test
    void unCreditNegatifOuUnDebitPositif_estRefuse() throws Exception {
        UUID user = newUser();
        assertThatThrownBy(() -> insertLedger(user, "TOP_UP", "-10.00", "0.00"))
                .hasMessageContaining("chk_wallet_tx_sign_matches_type");
        assertThatThrownBy(() -> insertLedger(user, "COMMISSION_DEDUCTED", "3.00", "3.00"))
                .hasMessageContaining("chk_wallet_tx_sign_matches_type");
        assertThatCode(() -> insertLedger(user, "TOP_UP", "10.00", "10.00")).doesNotThrowAnyException();
        assertThatCode(() -> insertLedger(user, "COMMISSION_DEDUCTED", "-3.00", "7.00")).doesNotThrowAnyException();
    }

    @Test
    void leGrandLivreNeSeModifieNiNeSeSupprime() throws Exception {
        UUID user = newUser();
        UUID tx = insertLedger(user, "TOP_UP", "20.00", "20.00");

        assertThatThrownBy(() -> execute("UPDATE wallet_transactions SET amount = 2000 WHERE id = '" + tx + "'"))
                .hasMessageContaining("ajout seul");
        assertThatThrownBy(() -> execute("DELETE FROM wallet_transactions WHERE id = '" + tx + "'"))
                .hasMessageContaining("ajout seul");
    }

    @Test
    void paiement_rembourseEtCommissionBornesParLeMontant() throws Exception {
        assertThat(constraintDefinition("chk_payments_refunded_within_amount"))
                .contains("refunded_amount >= ").contains("refunded_amount <= amount");
        assertThat(constraintDefinition("chk_payments_commission_within_amount"))
                .contains("commission_amount <= amount");
    }

    @Test
    void pawapay_montantPositifEtTransactionPrestataireUnique() throws Exception {
        UUID user = newUser();
        String insert = "INSERT INTO pawapay_operations (id, kind, status, amount, currency, provider, country, "
                + "msisdn, msisdn_masked, purpose, user_id, provider_transaction_id, created_at, updated_at) "
                + "VALUES ('%s', 'REFUND', 'FAILED', %s, 'XOF', 'ORANGE_SEN', 'SN', 'enc', '+221 •••• 67', "
                + "'WALLET_REFUND', '" + user + "', %s, NOW(), NOW())";

        assertThatThrownBy(() -> execute(String.format(insert, UUID.randomUUID(), "0", "NULL")))
                .hasMessageContaining("chk_pawapay_ops_amount_positive");

        execute(String.format(insert, UUID.randomUUID(), "1500", "'txn-v309'"));
        assertThatThrownBy(() -> execute(String.format(insert, UUID.randomUUID(), "1500", "'txn-v309'")))
                .hasMessageContaining("uq_pawapay_ops_provider_transaction");
    }
}
