package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V208 — annule les threads AWAITING_TRIP créés par l'ancien flux (trajet
 * lié après acceptation), devenus inatteignables une fois start() exige un
 * trajet dès la création de l'offre (cf. spec 2026-08-16).
 */
class V208MigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDb() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDb() throws Exception {
        if (postgres != null) postgres.close();
    }

    private void resetAndMigrateTo(String target) {
        Flyway reset = flywayUpTo("207");
        reset.clean();
        reset.migrate();
        flywayUpTo(target).migrate();
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .cleanDisabled(false)
                .target(targetVersion)
                .load();
    }

    private UUID seedAwaitingTripThread() throws Exception {
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'ACTIVE', now(), now())
                """.formatted(senderId, "uid-s-" + senderId.toString().substring(0, 8),
                    "user-s-" + senderId.toString().substring(0, 8)));
            st.executeUpdate("""
                INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'ACTIVE', now(), now())
                """.formatted(travelerId, "uid-t-" + travelerId.toString().substring(0, 8),
                    "user-t-" + travelerId.toString().substring(0, 8)));
            st.executeUpdate("""
                INSERT INTO package_requests
                  (id, sender_id, departure_city, arrival_city, desired_date,
                   date_tolerance_days, weight_kg, parcel_size, content_category,
                   negotiable, transport_mode, accepted_payment_methods, status, created_at, updated_at)
                VALUES
                  ('%s', '%s', 'Paris', 'Dakar', CURRENT_DATE + 10,
                   2, 3.0, 'MEDIUM', 'Documents',
                   true, 'PLANE', '{STRIPE}', 'NEGOTIATING', now(), now())
                """.formatted(requestId, senderId));
            st.executeUpdate("""
                INSERT INTO negotiation_threads
                  (id, package_request_id, traveler_id, traveler_travel_date,
                   traveler_available_kg, status, current_price_eur, created_at, updated_at)
                VALUES
                  ('%s', '%s', '%s', CURRENT_DATE + 10, 5.0, 'AWAITING_TRIP', 42.0, now(), now())
                """.formatted(threadId, requestId, travelerId));
        }
        return threadId;
    }

    @Test
    void beforeV208_awaitingTripThreadStaysUntouched() throws Exception {
        resetAndMigrateTo("207");
        UUID threadId = seedAwaitingTripThread();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery(
                    "SELECT status FROM negotiation_threads WHERE id = '" + threadId + "'");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("AWAITING_TRIP");
        }
    }

    @Test
    void afterV208_awaitingTripThreadIsCancelled() throws Exception {
        resetAndMigrateTo("207");
        UUID threadId = seedAwaitingTripThread();

        flywayUpTo("208").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery(
                    "SELECT status FROM negotiation_threads WHERE id = '" + threadId + "'");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("CANCELLED");
        }
    }

    @Test
    void afterV208_auditLogEntryCreated() throws Exception {
        resetAndMigrateTo("207");
        UUID threadId = seedAwaitingTripThread();

        flywayUpTo("208").migrate();

        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            var rs = st.executeQuery("""
                SELECT count(*) FROM audit_log
                WHERE entity_type = 'NEGOTIATION_THREAD'
                  AND entity_id = '%s'
                  AND action = 'AUTO_CANCELLED_MIGRATION_V208'
                """.formatted(threadId));
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void afterV208_otherStatusesUntouched() throws Exception {
        resetAndMigrateTo("208");
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        UUID openThreadId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'ACTIVE', now(), now())
                """.formatted(senderId, "uid-s2-" + senderId.toString().substring(0, 8),
                    "user-s2-" + senderId.toString().substring(0, 8)));
            st.executeUpdate("""
                INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'ACTIVE', now(), now())
                """.formatted(travelerId, "uid-t2-" + travelerId.toString().substring(0, 8),
                    "user-t2-" + travelerId.toString().substring(0, 8)));
            st.executeUpdate("""
                INSERT INTO package_requests
                  (id, sender_id, departure_city, arrival_city, desired_date,
                   date_tolerance_days, weight_kg, parcel_size, content_category,
                   negotiable, transport_mode, accepted_payment_methods, status, created_at, updated_at)
                VALUES
                  ('%s', '%s', 'Paris', 'Dakar', CURRENT_DATE + 10,
                   2, 3.0, 'MEDIUM', 'Documents',
                   true, 'PLANE', '{STRIPE}', 'NEGOTIATING', now(), now())
                """.formatted(requestId, senderId));
            // Thread OPEN seedé APRÈS la migration V208 : ne doit jamais être touché
            // (la migration ne cible que les lignes AWAITING_TRIP au moment où elle tourne).
            st.executeUpdate("""
                INSERT INTO negotiation_threads
                  (id, package_request_id, traveler_id, traveler_travel_date,
                   traveler_available_kg, status, current_price_eur, created_at, updated_at)
                VALUES
                  ('%s', '%s', '%s', CURRENT_DATE + 10, 5.0, 'OPEN', 42.0, now(), now())
                """.formatted(openThreadId, requestId, travelerId));

            var rs = st.executeQuery(
                    "SELECT status FROM negotiation_threads WHERE id = '" + openThreadId + "'");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("OPEN");
        }
    }
}
