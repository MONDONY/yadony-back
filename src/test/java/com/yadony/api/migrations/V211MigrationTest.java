package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V211 — étend la contrainte CHECK de {@code negotiation_threads.status} pour
 * accepter AWAITING_COMMISSION : un accord en espèces conclu par l'expéditeur
 * mais suspendu tant que le voyageur n'a pas réglé la commission Yadony
 * (cf. spec 2026-08-16, changement de conception).
 */
class V211MigrationTest {

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
        Flyway reset = flywayUpTo("210");
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

    /**
     * Insère un sender, un traveler et une package_request, puis retourne
     * l'id de la package_request — préalable partagé par toutes les insertions
     * de thread ci-dessous.
     */
    private UUID seedSenderAndRequest(Connection c, UUID senderId, UUID travelerId) throws SQLException {
        UUID requestId = UUID.randomUUID();
        try (Statement st = c.createStatement()) {
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
        }
        return requestId;
    }

    private void insertThreadWithStatus(Connection c, UUID requestId, UUID travelerId, String status)
            throws SQLException {
        UUID threadId = UUID.randomUUID();
        try (Statement st = c.createStatement()) {
            st.executeUpdate("""
                INSERT INTO negotiation_threads
                  (id, package_request_id, traveler_id, traveler_travel_date,
                   traveler_available_kg, status, current_price_eur, created_at, updated_at)
                VALUES
                  ('%s', '%s', '%s', CURRENT_DATE + 10, 5.0, '%s', 42.0, now(), now())
                """.formatted(threadId, requestId, travelerId, status));
        }
    }

    @Test
    void afterV211_awaitingCommissionStatusIsAccepted() throws Exception {
        resetAndMigrateTo("211");
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        try (Connection c = dataSource.getConnection()) {
            UUID requestId = seedSenderAndRequest(c, senderId, travelerId);

            insertThreadWithStatus(c, requestId, travelerId, "AWAITING_COMMISSION");

            try (Statement st = c.createStatement()) {
                var rs = st.executeQuery(
                        "SELECT count(*) FROM negotiation_threads WHERE package_request_id = '"
                                + requestId + "' AND status = 'AWAITING_COMMISSION'");
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void afterV211_previouslyAllowedStatusesStillAccepted() throws Exception {
        resetAndMigrateTo("211");
        List<String> historicalStatuses = List.of(
                "OPEN", "AWAITING_TRIP", "AWAITING_PAYMENT", "ACCEPTED",
                "REJECTED", "AUTO_REJECTED", "EXPIRED", "CANCELLED");

        try (Connection c = dataSource.getConnection()) {
            for (String status : historicalStatuses) {
                UUID senderId = UUID.randomUUID();
                UUID travelerId = UUID.randomUUID();
                UUID requestId = seedSenderAndRequest(c, senderId, travelerId);

                insertThreadWithStatus(c, requestId, travelerId, status);

                try (Statement st = c.createStatement()) {
                    var rs = st.executeQuery(
                            "SELECT count(*) FROM negotiation_threads WHERE package_request_id = '"
                                    + requestId + "' AND status = '" + status + "'");
                    rs.next();
                    assertThat(rs.getInt(1))
                            .as("statut historique %s toujours accepté après V211", status)
                            .isEqualTo(1);
                }
            }
        }
    }

    @Test
    void afterV211_unknownStatusIsStillRejected() throws Exception {
        resetAndMigrateTo("211");
        UUID senderId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();

        try (Connection c = dataSource.getConnection()) {
            UUID requestId = seedSenderAndRequest(c, senderId, travelerId);

            assertThatThrownBy(() -> insertThreadWithStatus(c, requestId, travelerId, "NOT_A_STATUS"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("chk_neg_thread_status");
        }
    }
}
