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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V212 — ajoute {@code negotiation_threads.commission_retry_count}, discriminant
 * de réessai de la clé d'idempotence Stripe du règlement de commission cash.
 *
 * <p>Ce test existe parce que le profil de test désactive Flyway et génère le
 * schéma H2 depuis les entités JPA : la SQL de la migration n'est vérifiée par
 * rien d'autre. Un {@code ADD COLUMN NOT NULL} sur une table déjà peuplée est
 * précisément le genre de migration qui passe en développement (table vide) et
 * échoue en production.
 */
class V212MigrationTest {

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
        Flyway reset = flywayUpTo("211");
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
                   true, 'PLANE', '{CASH}', 'NEGOTIATING', now(), now())
                """.formatted(requestId, senderId));
        }
        return requestId;
    }

    private UUID insertThread(Connection c, UUID requestId, UUID travelerId, String status) throws SQLException {
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
        return threadId;
    }

    private int readRetryCount(Connection c, UUID threadId) throws SQLException {
        try (Statement st = c.createStatement()) {
            var rs = st.executeQuery(
                    "SELECT commission_retry_count FROM negotiation_threads WHERE id = '" + threadId + "'");
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void afterV212_newThreadDefaultsToZeroRetries() throws Exception {
        resetAndMigrateTo("212");

        try (Connection c = dataSource.getConnection()) {
            UUID senderId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID requestId = seedSenderAndRequest(c, senderId, travelerId);

            UUID threadId = insertThread(c, requestId, travelerId, "AWAITING_COMMISSION");

            assertThat(readRetryCount(c, threadId))
                    .as("un thread inséré sans la colonne démarre à 0 réessai")
                    .isZero();
        }
    }

    /**
     * Le vrai risque de cette migration : la table n'est pas vide en production.
     * Un {@code ADD COLUMN NOT NULL} sans DEFAULT échouerait ici.
     */
    @Test
    void v212_backfillsExistingThreadsWithZero() throws Exception {
        resetAndMigrateTo("211");

        UUID threadId;
        try (Connection c = dataSource.getConnection()) {
            UUID senderId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID requestId = seedSenderAndRequest(c, senderId, travelerId);
            threadId = insertThread(c, requestId, travelerId, "AWAITING_COMMISSION");
        }

        flywayUpTo("212").migrate();

        try (Connection c = dataSource.getConnection()) {
            assertThat(readRetryCount(c, threadId))
                    .as("un thread antérieur à V212 est rétro-rempli à 0, pas laissé NULL")
                    .isZero();
        }
    }

    @Test
    void afterV212_nullRetryCountIsRejected() throws Exception {
        resetAndMigrateTo("212");

        try (Connection c = dataSource.getConnection()) {
            UUID senderId = UUID.randomUUID();
            UUID travelerId = UUID.randomUUID();
            UUID requestId = seedSenderAndRequest(c, senderId, travelerId);
            UUID threadId = insertThread(c, requestId, travelerId, "AWAITING_COMMISSION");

            assertThatThrownBy(() -> {
                try (Statement st = c.createStatement()) {
                    st.executeUpdate("UPDATE negotiation_threads SET commission_retry_count = NULL WHERE id = '"
                            + threadId + "'");
                }
            })
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("commission_retry_count");
        }
    }
}
