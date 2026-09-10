package com.yadony.api.migrations;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V253 : AWAITING_DEPOSIT accepté par la contrainte de statut, colonne deposit_expires_at.
 * Migre un PostgreSQL embarqué jusqu'à V252 (statut refusé), puis V253 (statut accepté). Le
 * profil "test" tourne sur H2 avec Flyway désactivé, incapable de vérifier une contrainte
 * CHECK PostgreSQL (même leçon que V250PaymentsDropPawapayRefsMigrationTest) : on lit donc
 * directement la définition de la contrainte via pg_get_constraintdef plutôt que d'insérer
 * une ligne dans negotiation_threads (FK vers package_requests et users).
 */
class V253NegotiationThreadsMobileMoneyMigrationTest {

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

    private static String statusConstraintDefinition() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = 'chk_neg_thread_status'");
            assertThat(rs.next()).as("la contrainte chk_neg_thread_status doit exister").isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void v253_allowsAwaitingDeposit_andAddsDepositExpiresAt() throws Exception {
        Flyway upTo252 = flywayUpTo("252");
        upTo252.clean();
        upTo252.migrate();
        assertThat(statusConstraintDefinition())
                .as("avant V253 la contrainte ne connaît pas AWAITING_DEPOSIT")
                .doesNotContain("AWAITING_DEPOSIT");

        flywayUpTo("253").migrate();

        assertThat(statusConstraintDefinition())
                .as("après V253 la contrainte accepte AWAITING_DEPOSIT")
                .contains("AWAITING_DEPOSIT");

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            var rs = s.executeQuery("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_name = 'negotiation_threads' AND column_name = 'deposit_expires_at'");
            assertThat(rs.next()).isTrue();
        }
    }
}
