package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V234CompleteTripRecurrencesMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void resetSchema() {
        Flyway baseline = flywayUpTo("230");
        baseline.clean();
        baseline.migrate();
    }

    @Test
    void historicalRecurrenceGetsCompatibleScheduleDefaults() throws Exception {
        UUID recurrenceId;
        LocalDate createdDate = LocalDate.of(2026, 8, 10);
        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            recurrenceId = seedRecurrence(connection, userId, createdDate);
        }

        flywayUpTo("234").migrate();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            var result = statement.executeQuery("""
                    SELECT start_date, end_date, week_interval, publication_lead_days,
                           handover_lead_days, pricing_mode, negotiable, currency
                    FROM trip_recurrences
                    WHERE id = '%s'
                    """.formatted(recurrenceId));
            assertThat(result.next()).isTrue();
            assertThat(result.getObject("start_date", LocalDate.class)).isEqualTo(createdDate);
            assertThat(result.getObject("end_date", LocalDate.class)).isNull();
            assertThat(result.getInt("week_interval")).isEqualTo(1);
            assertThat(result.getInt("publication_lead_days")).isEqualTo(14);
            assertThat(result.getInt("handover_lead_days")).isZero();
            assertThat(result.getString("pricing_mode")).isEqualTo("KG");
            assertThat(result.getBoolean("negotiable")).isFalse();
            assertThat(result.getString("currency")).isEqualTo("EUR");
        }
    }

    @Test
    void generatedAnnouncementIsUniqueForRecurrenceAndDepartureDate() throws Exception {
        flywayUpTo("234").migrate();

        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            UUID recurrenceId = seedRecurrence(connection, userId, LocalDate.now());
            LocalDate departureDate = LocalDate.now().plusDays(14);
            seedAnnouncement(connection, userId, recurrenceId, departureDate);

            assertThatThrownBy(() -> seedAnnouncement(connection, userId, recurrenceId, departureDate))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("uq_announcements_recurrence_departure");
        }
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target(targetVersion)
                .cleanDisabled(false)
                .load();
    }

    private UUID seedUser(Connection connection) throws Exception {
        UUID id = UUID.randomUUID();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                    VALUES ('%s', 'uid-%s', 'user-%s', 'ACTIVE', now(), now())
                    """.formatted(id, id, id.toString().substring(0, 12)));
        }
        return id;
    }

    private UUID seedRecurrence(Connection connection, UUID userId, LocalDate createdDate) throws Exception {
        UUID id = UUID.randomUUID();
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO trip_recurrences (
                        id, user_id, departure_city, arrival_city, available_kg, price_per_kg,
                        pickup_label, pickup_lat, pickup_lng, delivery_label, delivery_lat,
                        delivery_lng, weekdays, horizon_days, created_at, updated_at)
                    VALUES (
                        '%s', '%s', 'Paris', 'Dakar', 23, 8,
                        '12 rue de la Paix', 48.86, 2.33, 'Aeroport de Dakar', 14.73,
                        -17.49, '1000100', 14, '%s', '%s')
                    """.formatted(id, userId, createdDate.atStartOfDay(), createdDate.atStartOfDay()));
        }
        return id;
    }

    private void seedAnnouncement(
            Connection connection,
            UUID travelerId,
            UUID recurrenceId,
            LocalDate departureDate
    ) throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO announcements (
                    id, traveler_id, departure_city, arrival_city, departure_date,
                    available_kg, price_per_kg, transport_mode, total_kg,
                    pickup_address_label, pickup_lat, pickup_lng,
                    delivery_address_label, delivery_lat, delivery_lng,
                    source_recurrence_id, created_at, updated_at)
                VALUES (?, ?, 'Paris', 'Dakar', ?, 23, 8, 'PLANE', 23,
                    '12 rue de la Paix', 48.86, 2.33,
                    'Aeroport de Dakar', 14.73, -17.49, ?, now(), now())
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, travelerId);
            statement.setDate(3, Date.valueOf(departureDate));
            statement.setObject(4, recurrenceId);
            statement.executeUpdate();
        }
    }
}
