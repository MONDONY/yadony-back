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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V268 : décisions d'administration sur une vérification d'identité. Cinq colonnes
 * nullables dans {@code kyc_schema.kyc_verifications}, un CHECK sur le type de décision, et
 * le rattrapage de {@code submitted_at} pour les lignes déjà en revue chez le fournisseur.
 */
class V268KycAdminDecisionsMigrationTest {

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

    private static String columnInfo(String column) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT data_type, is_nullable, character_maximum_length "
                    + "FROM information_schema.columns WHERE table_schema = 'kyc_schema' "
                    + "AND table_name = 'kyc_verifications' AND column_name = '" + column + "'");
            return rs.next() ? rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) : null;
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String submittedAt(UUID kycId) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT submitted_at FROM kyc_schema.kyc_verifications WHERE id = '"
                    + kycId + "'");
            rs.next();
            return rs.getString(1);
        }
    }

    private static UUID insertKyc(String status) throws SQLException {
        UUID userId = UUID.randomUUID();
        UUID kycId = UUID.randomUUID();
        exec("INSERT INTO users (id, firebase_uid, username, status, kyc_status, created_at, updated_at) VALUES ('"
                + userId + "', 'uid-" + userId + "', 'u" + userId.toString().substring(0, 12)
                + "', 'ACTIVE', 'PENDING', now(), now())");
        exec("INSERT INTO kyc_schema.kyc_verifications (id, user_id, verification_session_id, provider, status, "
                + "created_at, updated_at) VALUES ('" + kycId + "', '" + userId + "', 'sess-" + kycId
                + "', 'DIDIT', '" + status + "', now(), now())");
        return kycId;
    }

    private static void audit(UUID kycId, String action, String at) throws SQLException {
        exec("INSERT INTO audit_log (entity_type, entity_id, action, created_at) VALUES ('kyc_verification', '"
                + kycId + "', '" + action + "', TIMESTAMP '" + at + "')");
    }

    @Test
    void v268_ajouteLesColonnesDeDecision_etRattrapeLesRevuesEnCours() throws Exception {
        Flyway baseline = flywayUpTo("267");
        baseline.clean();
        baseline.migrate();
        assertThat(columnInfo("decision_kind")).isNull();

        UUID enRevue = insertKyc("PENDING");
        audit(enRevue, "KYC_SESSION_CREATED", "2026-09-01 10:00:00");
        audit(enRevue, "KYC_IN_REVIEW", "2026-09-01 10:05:00");

        UUID relance = insertKyc("PENDING");
        audit(relance, "KYC_IN_REVIEW", "2026-09-01 10:05:00");
        audit(relance, "KYC_SESSION_CREATED", "2026-09-02 08:00:00");

        UUID verifiee = insertKyc("VERIFIED");
        audit(verifiee, "KYC_IN_REVIEW", "2026-09-01 10:05:00");

        flywayUpTo("268").migrate();

        assertThat(columnInfo("decided_by_admin_id")).isEqualTo("uuid|YES|null");
        assertThat(columnInfo("decided_at")).isEqualTo("timestamp without time zone|YES|null");
        assertThat(columnInfo("decision_reason")).isEqualTo("character varying|YES|1000");
        assertThat(columnInfo("decision_kind")).isEqualTo("character varying|YES|20");
        assertThat(columnInfo("submitted_at")).isEqualTo("timestamp without time zone|YES|null");

        assertThat(submittedAt(enRevue)).startsWith("2026-09-01 10:05:00");
        assertThat(submittedAt(relance)).as("une session recréée après la revue n'est plus en revue").isNull();
        assertThat(submittedAt(verifiee)).as("seules les lignes PENDING sont rattrapées").isNull();

        exec("UPDATE kyc_schema.kyc_verifications SET decision_kind = 'REVOKED' WHERE id = '" + verifiee + "'");
        assertThatThrownBy(() -> exec("UPDATE kyc_schema.kyc_verifications SET decision_kind = 'CANCELLED' WHERE id = '"
                + verifiee + "'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("kyc_verifications_decision_kind_check");
    }
}
