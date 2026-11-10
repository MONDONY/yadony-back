package com.yadony.api.kyc.reminder;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V365 sur Postgres embarque : Flyway jusqu'a V364, quatre comptes avec ligne KYC, puis la
 * migration. Seul le compte non verifie avec une session ouverte il y a moins de 90 jours
 * recoit une ancre. Flyway est pilote a la main pour s'arreter a V364 (pas de contexte Spring).
 */
@DisplayName("Relances KYC : migration V365 (Postgres)")
class KycReminderMigrationPostgresIT {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(postgres.getPostgresDatabase())
                .locations("classpath:db/migration")
                .target(target)
                .load();
    }

    private UUID insert(Statement st, String kycStatus, String sessionId, int updatedDaysAgo) throws Exception {
        UUID id = UUID.randomUUID();
        st.execute("INSERT INTO users (id, firebase_uid, username, kyc_status) VALUES ('" + id
                + "', 'uid-" + id + "', 'u" + id.toString().substring(0, 8) + "', '" + kycStatus + "')");
        String session = sessionId == null ? "NULL" : "'" + sessionId + "'";
        st.execute("INSERT INTO kyc_schema.kyc_verifications (user_id, verification_session_id, status, updated_at)"
                + " VALUES ('" + id + "', " + session + ", 'PENDING', NOW() - INTERVAL '" + updatedDaysAgo + " days')");
        return id;
    }

    @Test
    @DisplayName("le stock initial ne couvre que les non verifies avec session recente")
    void backfillOnlyRecentUnverifiedWithSession() throws Exception {
        flyway("364").migrate();

        Map<String, UUID> ids = new HashMap<>();
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            ids.put("j89", insert(st, "PENDING", "sess_a", 89));
            ids.put("j91", insert(st, "PENDING", "sess_b", 91));
            ids.put("j10SansSession", insert(st, "NOT_STARTED", null, 10));
            ids.put("j10Verifie", insert(st, "VERIFIED", "sess_c", 10));
        }

        flyway("365").migrate();

        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement st = c.createStatement()) {
            for (var e : ids.entrySet()) {
                try (ResultSet rs = st.executeQuery("SELECT kyc_reminder_anchor_at, kyc_reminder_count FROM users WHERE id = '"
                        + e.getValue() + "'")) {
                    assertThat(rs.next()).isTrue();
                    boolean anchored = rs.getTimestamp(1) != null;
                    assertThat(anchored).as(e.getKey()).isEqualTo(e.getKey().equals("j89"));
                    assertThat(rs.getInt(2)).as(e.getKey() + " compteur").isZero();
                }
            }
        }
    }
}
