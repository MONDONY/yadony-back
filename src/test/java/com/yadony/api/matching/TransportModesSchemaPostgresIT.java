package com.yadony.api.matching;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
 * V379 (socle des modes de transport) sur un vrai PostgreSQL : durée d'arrivée jusqu'à 120 jours,
 * demande de colis sans mode imposé, motif de report TRANSPORT_CANCELLED, garde-fous sur le mode
 * des modèles et séries. Flyway joue toutes les migrations jusqu'à la dernière.
 */
@DisplayName("Modes de transport - schéma V379")
class TransportModesSchemaPostgresIT {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static UUID traveler;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        exec("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("379").cleanDisabled(false).load().migrate();
        traveler = UUID.randomUUID();
        exec("INSERT INTO users (id, firebase_uid, username) VALUES ('" + traveler + "', 'v379-" + traveler
                + "', 'v379_" + traveler.toString().substring(0, 8) + "')");
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    @DisplayName("une annonce BATEAU arrive à J+120, pas à J+121")
    void boatAnnouncement_day120Ok_day121Rejected() throws Exception {
        UUID ok = announcement("BOAT", 120);
        assertThat(ok).isNotNull();

        assertThatThrownBy(() -> announcement("BOAT", 121))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_arrival_date");
    }

    @Test
    @DisplayName("une arrivée avant le départ reste rejetée")
    void arrivalBeforeDeparture_stillRejected() {
        assertThatThrownBy(() -> announcement("BOAT", -1))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_announcements_arrival_date");
    }

    @Test
    @DisplayName("une demande de colis sans mode de transport est acceptée")
    void packageRequest_nullTransportMode_ok() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT is_nullable FROM information_schema.columns "
                     + "WHERE table_name = 'package_requests' AND column_name = 'transport_mode'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("YES");
        }
        // Le CHECK existant continue de refuser une valeur inconnue.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                     + "WHERE conname = 'chk_pkg_req_transport_mode'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("PLANE").contains("BOAT");
        }
    }

    @Test
    @DisplayName("une reprogrammation TRANSPORT_CANCELLED est acceptée, un motif inconnu refusé")
    void reschedule_transportCancelledOk_unknownRejected() throws Exception {
        UUID ann = announcement("PLANE", 1);
        reschedule(ann, "TRANSPORT_CANCELLED");
        reschedule(ann, "FLIGHT_CANCELLED");

        assertThatThrownBy(() -> reschedule(ann, "NOPE"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_trip_reschedules_reason");
    }

    @Test
    @DisplayName("les modèles et séries bornent l'arrivée à 120 jours et le mode aux valeurs connues")
    void templatesAndRecurrences_constraints() throws Exception {
        assertThat(constraint("chk_trip_templates_arrival_day_offset")).contains("120");
        assertThat(constraint("chk_trip_recurrences_arrival_day_offset")).contains("120");
        assertThat(constraint("chk_trip_templates_transport_mode")).contains("BOAT");
        assertThat(constraint("chk_trip_recurrences_transport_mode")).contains("BOAT");
    }

    private static String constraint(String name) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = '"
                     + name + "'")) {
            assertThat(rs.next()).as("contrainte %s présente", name).isTrue();
            return rs.getString(1);
        }
    }

    /** Départ à J+10 ; l'arrivée tombe {@code daysAfterDeparture} jours plus tard. */
    private static UUID announcement(String mode, int daysAfterDeparture) throws Exception {
        UUID id = UUID.randomUUID();
        exec("""
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  arrival_date, available_kg, price_per_kg, transport_mode, total_kg,
                  pickup_address_label, pickup_lat, pickup_lng,
                  delivery_address_label, delivery_lat, delivery_lng,
                  status, created_at, updated_at)
                VALUES ('%s', '%s', 'Marseille', 'Alger', CURRENT_DATE + 10,
                  CURRENT_DATE + 10 + %d, 3.00, 15.00, '%s', 3.00,
                  '12 rue de Paris', 48.8566, 2.3522,
                  'Plateau, Alger', 36.75, 3.05,
                  'ACTIVE', now(), now())
                """.formatted(id, traveler, daysAfterDeparture, mode));
        return id;
    }

    private static void reschedule(UUID announcementId, String reason) throws Exception {
        exec("""
                INSERT INTO trip_reschedules (id, announcement_id, traveler_id, reason, previous_departure_date,
                  new_departure_date, created_at)
                VALUES ('%s', '%s', '%s', '%s', CURRENT_DATE + 10, CURRENT_DATE + 20, now())
                """.formatted(UUID.randomUUID(), announcementId, traveler, reason));
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }
}
