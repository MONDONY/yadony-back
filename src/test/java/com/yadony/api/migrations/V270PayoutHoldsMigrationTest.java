package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * V270 : gel des versements (payout_holds), marque payments.payout_held_at, registre des sessions
 * KYC refusees, et rattrapage des comptes deja bannis ou revoques avant le deploiement.
 */
class V270PayoutHoldsMigrationTest {

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

    @BeforeEach
    void resetSchema() {
        Flyway baseline = flywayUpTo("269");
        baseline.clean();
        baseline.migrate();
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    @Test
    void rattrapage_geleLesBannisVivants_maisPasLesComptesFinalises() throws Exception {
        UUID banned = insertUser("BANNED", "NOT_STARTED", false);
        UUID finalized = insertUser("BANNED", "NOT_STARTED", true);
        UUID active = insertUser("ACTIVE", "VERIFIED", false);
        UUID suspended = insertUser("SUSPENDED", "VERIFIED", false);
        exec("INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload, created_at) VALUES "
                + "('USER', '" + banned + "', 'USER_BANNED_BY_ADMIN', NULL, '{}', '2026-09-01 10:00:00+00')");

        flywayUpTo("270").migrate();

        assertThat(activeHolds(banned)).containsExactly("BANNED");
        assertThat(heldSince(banned, "BANNED")).startsWith("2026-09-01 10:00");
        assertThat(activeHolds(finalized)).isEmpty();
        assertThat(activeHolds(active)).isEmpty();
        assertThat(activeHolds(suspended)).isEmpty();
    }

    @Test
    void rattrapage_geleLesKycRevoques_memeApresUneNouvelleSession_saufReverifies() throws Exception {
        // Revoque, decision encore sur la ligne.
        UUID revoked = insertUser("ACTIVE", "REJECTED", false);
        UUID revokedKyc = insertKyc(revoked, "REJECTED", "sess-rev", "'REVOKED'");
        exec("UPDATE kyc_schema.kyc_verifications SET decided_at = '2026-09-10 08:00:00' WHERE id = '" + revokedKyc + "'");
        // Revoque puis nouvelle session : decision effacee, seule l'audit en garde la trace.
        UUID restarted = insertUser("ACTIVE", "PENDING", false);
        UUID restartedKyc = insertKyc(restarted, "PENDING", "sess-new", "NULL");
        audit("kyc_verification", restartedKyc, "KYC_REVOKED_BY_ADMIN", "{\"sessionId\":\"sess-old\"}", "2026-09-05 09:00:00+00");
        // Revoque puis reverifie : plus rien a geler.
        UUID reverified = insertUser("ACTIVE", "VERIFIED", false);
        UUID reverifiedKyc = insertKyc(reverified, "VERIFIED", "sess-ok", "'APPROVED'");
        audit("kyc_verification", reverifiedKyc, "KYC_REVOKED_BY_ADMIN", "{\"sessionId\":\"sess-first\"}", "2026-09-05 09:00:00+00");
        audit("kyc_verification", reverifiedKyc, "KYC_VERIFIED", "{\"sessionId\":\"sess-ok\"}", "2026-09-06 09:00:00+00");
        // Simple refus : aucun gel (seule la revocation gele).
        UUID rejected = insertUser("ACTIVE", "REJECTED", false);
        insertKyc(rejected, "REJECTED", "sess-rej", "'REJECTED'");

        flywayUpTo("270").migrate();

        assertThat(activeHolds(revoked)).containsExactly("KYC_REVOKED");
        assertThat(heldSince(revoked, "KYC_REVOKED")).startsWith("2026-09-10 08:00");
        assertThat(activeHolds(restarted)).containsExactly("KYC_REVOKED");
        assertThat(activeHolds(reverified)).isEmpty();
        assertThat(activeHolds(rejected)).isEmpty();
    }

    @Test
    void rattrapage_banniEtRevoque_deuxGels() throws Exception {
        UUID both = insertUser("BANNED", "REJECTED", false);
        insertKyc(both, "REJECTED", "sess-both", "'REVOKED'");

        flywayUpTo("270").migrate();

        assertThat(activeHolds(both)).containsExactlyInAnyOrder("BANNED", "KYC_REVOKED");
    }

    @Test
    void rattrapage_memoriseLesSessionsRefuseesPourLesLignesEtLHistorique() throws Exception {
        UUID u1 = insertUser("ACTIVE", "REJECTED", false);
        insertKyc(u1, "REJECTED", "sess-line", "'REJECTED'");
        UUID u2 = insertUser("ACTIVE", "PENDING", false);
        UUID k2 = insertKyc(u2, "PENDING", "sess-current", "NULL");
        audit("kyc_verification", k2, "KYC_REJECTED_BY_ADMIN", "{\"sessionId\":\"sess-history\"}", "2026-09-05 09:00:00+00");
        audit("kyc_verification", k2, "KYC_REJECTED_BY_ADMIN", "{\"sessionId\":\"\"}", "2026-09-05 09:30:00+00");

        flywayUpTo("270").migrate();

        assertThat(refusedSessions()).containsExactlyInAnyOrder("sess-line", "sess-history");
    }

    @Test
    void schema_contraintesEtIndexUnique() throws Exception {
        UUID user = insertUser("ACTIVE", "VERIFIED", false);
        flywayUpTo("270").migrate();

        exec("INSERT INTO payout_holds (user_id, reason, held_since) VALUES ('" + user + "', 'BANNED', now())");
        assertThatThrownBy(() -> exec("INSERT INTO payout_holds (user_id, reason, held_since) VALUES ('"
                + user + "', 'BANNED', now())"))
                .isInstanceOf(SQLException.class).hasMessageContaining("uq_payout_holds_active");
        // Un gel leve libere la place d'un nouveau gel du meme motif.
        exec("UPDATE payout_holds SET released_at = now() WHERE user_id = '" + user + "'");
        exec("INSERT INTO payout_holds (user_id, reason, held_since) VALUES ('" + user + "', 'BANNED', now())");
        assertThatThrownBy(() -> exec("INSERT INTO payout_holds (user_id, reason, held_since) VALUES ('"
                + user + "', 'SUSPENDED', now())"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_payout_holds_reason");
        assertThatThrownBy(() -> exec("INSERT INTO kyc_schema.kyc_refused_sessions (user_id, session_id, decision_kind, decided_at) "
                + "VALUES ('" + user + "', 's', 'APPROVED', now())"))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_kyc_refused_sessions_kind");
        assertThat(queryString("SELECT data_type FROM information_schema.columns WHERE table_name = 'payments' "
                + "AND column_name = 'payout_held_at'")).isEqualTo("timestamp without time zone");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static UUID insertUser(String status, String kycStatus, boolean deleted) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO users (id, firebase_uid, username, status, kyc_status, created_at, updated_at, deleted_at) VALUES ('"
                + id + "', 'uid-" + id + "', 'u" + id.toString().substring(0, 12) + "', '" + status + "', '"
                + kycStatus + "', now(), now(), " + (deleted ? "now()" : "NULL") + ")");
        return id;
    }

    private static UUID insertKyc(UUID userId, String status, String sessionId, String decisionKindSql) throws SQLException {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO kyc_schema.kyc_verifications (id, user_id, verification_session_id, provider, status, "
                + "decision_kind, created_at, updated_at) VALUES ('" + id + "', '" + userId + "', '" + sessionId
                + "', 'DIDIT', '" + status + "', " + decisionKindSql + ", now(), now())");
        return id;
    }

    private static void audit(String type, UUID entityId, String action, String payload, String at) throws SQLException {
        exec("INSERT INTO audit_log (entity_type, entity_id, action, actor_id, payload, created_at) VALUES ('"
                + type + "', '" + entityId + "', '" + action + "', NULL, '" + payload + "', '" + at + "')");
    }

    private static List<String> activeHolds(UUID userId) throws SQLException {
        return queryList("SELECT reason FROM payout_holds WHERE user_id = '" + userId
                + "' AND released_at IS NULL ORDER BY reason");
    }

    private static String heldSince(UUID userId, String reason) throws SQLException {
        return queryString("SELECT held_since::text FROM payout_holds WHERE user_id = '" + userId
                + "' AND reason = '" + reason + "'");
    }

    private static List<String> refusedSessions() throws SQLException {
        return queryList("SELECT session_id FROM kyc_schema.kyc_refused_sessions");
    }

    private static List<String> queryList(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rs.next()) values.add(rs.getString(1));
            return values;
        }
    }

    private static String queryString(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
