package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V344 (YADONY-FLUTTER-PROD-1R) : recale en UTC les dates limites de dépôt que le serveur
 * écrivait en heure murale (trajets dédiés, occurrences de récurrence), et celles des demandes
 * qui les avaient copiées. Joué sur un vrai PostgreSQL : le profil test (H2) n'exécute pas Flyway.
 *
 * <p>Les valeurs sont semées comme Hibernate les écrit ({@code hibernate.jdbc.time_zone = UTC}) :
 * le {@code LocalDateTime} Java « L » devient l'instant {@code L+00}. La session est volontairement
 * réglée sur un autre fuseau pour prouver que la migration n'en dépend pas.
 */
class V344HandoverDeadlineLocalToUtcMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static UUID user;

    private static UUID dedicatedParis;
    private static UUID dedicatedParisBid;
    private static UUID dedicatedParisOtherBid;
    private static UUID dedicatedTorontoNoTime;
    private static UUID dedicatedEditedInApp;
    private static UUID dedicatedCompleted;
    private static UUID dedicatedDeleted;
    private static UUID dedicatedRescheduled;
    private static UUID dedicatedUnknownZone;
    private static UUID publicTrip;
    private static UUID recurrenceWithLead;
    private static UUID recurrenceFallbackOnDeparture;
    private static UUID recurrenceEditedInApp;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        exec("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        Flyway before = flywayUpTo("343");
        before.clean();
        before.migrate();

        user = seedUser();
        UUID request = seedPackageRequest();
        // Une récurrence par occurrence : (source_recurrence_id, departure_date) est unique.

        // Trajet dédié de Paris : départ 20:30 locale, limite écrite « 20:30 » (heure murale).
        dedicatedParis = trip("Europe/Paris", "2026-10-19", "20:30", "2026-10-19 20:30", "ACTIVE",
                request, null, 0, false);
        dedicatedParisBid = bid(dedicatedParis, "2026-10-19 20:30");
        dedicatedParisOtherBid = bid(dedicatedParis, "2026-10-19 18:00");
        // Toronto (UTC-4 en octobre), sans heure : 23:59 locale.
        dedicatedTorontoNoTime = trip("America/Toronto", "2026-10-19", null, "2026-10-19 23:59", "FULL",
                request, null, 0, false);
        // Limite modifiée depuis l'app (déjà en UTC) : ne reproduit pas la signature serveur.
        dedicatedEditedInApp = trip("Europe/Paris", "2026-10-19", "20:30", "2026-10-19 18:30", "ACTIVE",
                request, null, 0, false);
        dedicatedCompleted = trip("Europe/Paris", "2026-10-19", "20:30", "2026-10-19 20:30", "COMPLETED",
                request, null, 0, false);
        dedicatedDeleted = trip("Europe/Paris", "2026-10-19", "20:30", "2026-10-19 20:30", "ACTIVE",
                request, null, 0, true);
        dedicatedRescheduled = trip("Europe/Paris", "2026-10-19", "20:30", "2026-10-19 20:30", "ACTIVE",
                request, null, 1, false);
        dedicatedUnknownZone = trip("Mars/Olympus", "2026-10-19", "20:30", "2026-10-19 20:30", "IN_PROGRESS",
                request, null, 0, false);
        // Trajet public saisi dans l'app : déjà en UTC, jamais touché même s'il « ressemble ».
        publicTrip = trip("Europe/Paris", "2026-10-19", "20:30", "2026-10-19 20:30", "ACTIVE",
                null, null, 0, false);
        // Récurrence (délai de remise 2 j) : départ 25/10 14:00, limite 23/10 14:00 locale (CEST).
        recurrenceWithLead = trip("Europe/Paris", "2026-10-25", "14:00", "2026-10-23 14:00", "ACTIVE",
                null, seedRecurrence(2), 0, false);
        // Repli sur le départ lui-même (25/10 14:00, heure d'hiver CET depuis 03:00 ce jour-là).
        recurrenceFallbackOnDeparture = trip("Europe/Paris", "2026-10-25", "14:00", "2026-10-25 14:00", "DRAFT",
                null, seedRecurrence(2), 0, false);
        recurrenceEditedInApp = trip("Europe/Paris", "2026-10-25", "14:00", "2026-10-24 09:15", "ACTIVE",
                null, seedRecurrence(2), 0, false);

        flywayUpTo("344").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    void trajetDedieDeParis_heureMuraleRecaleeEnUtc_etSaDemandeAussi() throws Exception {
        assertThat(deadline("announcements", dedicatedParis)).isEqualTo("2026-10-19 18:30");
        assertThat(deadline("bids", dedicatedParisBid)).isEqualTo("2026-10-19 18:30");
        // Demande qui ne portait pas l'ancienne valeur du trajet : intacte.
        assertThat(deadline("bids", dedicatedParisOtherBid)).isEqualTo("2026-10-19 18:00");
    }

    @Test
    void trajetDedieDeToronto_sansHeure_2359LocaleDevient0359UtcLeLendemain() throws Exception {
        assertThat(deadline("announcements", dedicatedTorontoNoTime)).isEqualTo("2026-10-20 03:59");
    }

    @Test
    void fuseauInconnu_repliEuropeParis() throws Exception {
        assertThat(deadline("announcements", dedicatedUnknownZone)).isEqualTo("2026-10-19 18:30");
    }

    @Test
    void occurrencesDeRecurrence_recaleesAvecLHeureDEteOuDHiver() throws Exception {
        assertThat(deadline("announcements", recurrenceWithLead)).isEqualTo("2026-10-23 12:00");
        assertThat(deadline("announcements", recurrenceFallbackOnDeparture)).isEqualTo("2026-10-25 13:00");
    }

    @Test
    void rienNeBougeHorsSignatureServeur_horsStatutActif_ouApresReport() throws Exception {
        assertThat(deadline("announcements", dedicatedEditedInApp)).isEqualTo("2026-10-19 18:30");
        assertThat(deadline("announcements", dedicatedCompleted)).isEqualTo("2026-10-19 20:30");
        assertThat(deadline("announcements", dedicatedDeleted)).isEqualTo("2026-10-19 20:30");
        assertThat(deadline("announcements", dedicatedRescheduled)).isEqualTo("2026-10-19 20:30");
        assertThat(deadline("announcements", publicTrip)).isEqualTo("2026-10-19 20:30");
        assertThat(deadline("announcements", recurrenceEditedInApp)).isEqualTo("2026-10-24 09:15");
    }

    // ---------------------------------------------------------------- outils

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false)
                // Fuseau de session exotique : la migration doit tout convertir explicitement.
                .initSql("SET TIME ZONE 'Asia/Tokyo'")
                .load();
    }

    private static UUID seedUser() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("INSERT INTO users (firebase_uid, username) VALUES ('v344-" + suffix
                     + "', 'v344_" + suffix + "') RETURNING id")) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }

    private static UUID seedPackageRequest() throws Exception {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date, "
                + "weight_kg, parcel_size, content_category, transport_mode, status) VALUES ('" + id + "', '"
                + user + "', 'Paris', 'Dakar', CURRENT_DATE + 10, 5, 'SMALL', 'CLOTHES', 'PLANE', 'OPEN')");
        return id;
    }

    private static UUID seedRecurrence(int leadDays) throws Exception {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO trip_recurrences (id, user_id, departure_city, arrival_city, available_kg, "
                + "price_per_kg, pickup_label, pickup_lat, pickup_lng, delivery_label, delivery_lat, "
                + "delivery_lng, weekdays, horizon_days, handover_lead_days, created_at, updated_at) VALUES ('"
                + id + "', '" + user + "', 'Paris', 'Dakar', 23, 8, '12 rue de la Paix', 48.86, 2.33, "
                + "'Aeroport de Dakar', 14.73, -17.49, '1000100', 14, " + leadDays + ", now(), now())");
        return id;
    }

    /** Date limite semée comme Hibernate l'écrit : le LocalDateTime « L » devient l'instant L+00. */
    private static UUID trip(String timezone, String date, String time, String javaDeadline, String status,
                             UUID linkedRequest, UUID recurrence, int rescheduleCount, boolean deleted)
            throws Exception {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date, "
                + "departure_time, handover_deadline, timezone, status, available_kg, price_per_kg, "
                + "transport_mode, total_kg, pickup_address_label, pickup_lat, pickup_lng, "
                + "delivery_address_label, delivery_lat, delivery_lng, linked_package_request_id, "
                + "source_recurrence_id, reschedule_count, created_at, updated_at, deleted_at) VALUES ('"
                + id + "', '" + user + "', 'Paris', 'Dakar', DATE '" + date + "', "
                + (time == null ? "NULL" : "TIME '" + time + "'") + ", "
                + "TIMESTAMPTZ '" + javaDeadline + ":00+00', '" + timezone + "', '" + status + "', "
                + "3.00, 15.00, 'PLANE', 3.00, 'Départ', 0, 0, 'Arrivée', 0, 0, "
                + (linkedRequest == null ? "NULL" : "'" + linkedRequest + "'") + ", "
                + (recurrence == null ? "NULL" : "'" + recurrence + "'") + ", " + rescheduleCount
                + ", now(), now(), " + (deleted ? "now()" : "NULL") + ")");
        return id;
    }

    private static UUID bid(UUID announcement, String javaDeadline) throws Exception {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description, recipient_name, "
                + "recipient_phone, status, handover_deadline, created_at, updated_at) VALUES ('" + id + "', '"
                + announcement + "', '" + user + "', 3.00, 'Vetements', 'Aminata', '+221701234567', "
                + "'ACCEPTED', TIMESTAMPTZ '" + javaDeadline + ":00+00', now(), now())");
        return id;
    }

    /** Date limite relue comme Java la lit : l'instant exprimé en UTC. */
    private static String deadline(String table, UUID id) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_char(handover_deadline AT TIME ZONE 'UTC', "
                     + "'YYYY-MM-DD HH24:MI') FROM " + table + " WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
