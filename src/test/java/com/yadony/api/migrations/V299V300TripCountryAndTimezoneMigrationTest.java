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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V299 (rattrapage des codes pays, FLUTTER-EH) et V300 (fuseau des trajets à venir déduit
 * de la ville de départ), joués sur un vrai PostgreSQL avec des villes du vrai GeoNames
 * insérées AVANT les migrations, comme en staging où le chargeur a déjà rempli `cities`.
 */
class V299V300TripCountryAndTimezoneMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private static UUID user;
    private static final LocalDate FUTURE = LocalDate.now().plusDays(10);
    private static final LocalDate PAST = LocalDate.now().minusDays(10);

    // Codes pays (V299)
    private static UUID deletedNoCodes;
    private static UUID partialCodes;
    private static UUID swappedCodes;
    private static UUID wrongButNotSwapped;
    private static UUID unknownDepartureCity;
    private static UUID templateNoCodes;
    private static UUID templateWithCodes;

    // Fuseaux (V300)
    private static UUID abidjanUpcoming;
    private static UUID houstonUpcoming;
    private static UUID dakarWithoutDepartureAt;
    private static UUID parisUpcoming;
    private static UUID unknownCityKnownCountry;
    private static UUID cotonouCompleted;
    private static UUID abidjanPast;
    private static UUID abidjanCancelled;
    private static UUID abidjanDeleted;
    private static UUID abidjanWithoutTime;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        exec("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        // Dernière migration de main avant celles-ci (V296-V298 ne sont pas encore fusionnées).
        Flyway before = flywayUpTo("295");
        before.clean();
        before.migrate();

        city(2394819, "Cotonou", "BJ", 679012);
        city(2293538, "Abidjan", "CI", 6321017);
        city(2988507, "Paris", "FR", 2138551);
        city(4717560, "Paris", "US", 24782);
        city(2253354, "Dakar", "SN", 2646503);
        city(6167865, "Toronto", "CA", 2794356);
        city(4699066, "Houston", "US", 2314157);
        city(2646507, "Houston", "GB", 6420);
        city(999999999L, "Absenteville", "XX", 10);

        user = UUID.randomUUID();
        exec("INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at) "
                + "VALUES ('" + user + "', 'uid-" + user + "', 'u-" + user.toString().substring(0, 8)
                + "', 'ACTIVE', now(), now())");

        deletedNoCodes = trip("Paris", "Dakar", null, null, "ACTIVE", PAST, LocalTime.of(10, 0), true);
        partialCodes = trip("Cotonou", "Abidjan", "BJ", null, "COMPLETED", PAST, LocalTime.of(10, 0), false);
        swappedCodes = trip("Abidjan", "Paris", "FR", "CI", "COMPLETED", PAST, LocalTime.of(10, 0), false);
        wrongButNotSwapped = trip("paris ", "Dakar", "US", "SN", "COMPLETED", PAST, LocalTime.of(10, 0), false);
        unknownDepartureCity = trip("Atlantide", "DAKAR", null, null, "COMPLETED", PAST, LocalTime.of(10, 0), false);
        templateNoCodes = template("Abidjan", "Paris", null, null);
        templateWithCodes = template("Paris", "Dakar", "SN", "FR");

        abidjanUpcoming = trip("Abidjan", "Paris", "CI", "FR", "ACTIVE", FUTURE, LocalTime.of(10, 0), false);
        houstonUpcoming = trip("Houston", "Paris", "US", "FR", "FULL", FUTURE, LocalTime.of(8, 0), false);
        dakarWithoutDepartureAt = trip("Dakar", "Paris", "SN", "FR", "DRAFT", FUTURE, LocalTime.of(9, 0), false);
        exec("UPDATE announcements SET departure_at = NULL WHERE id = '" + dakarWithoutDepartureAt + "'");
        parisUpcoming = trip("Paris", "Dakar", "FR", "SN", "ACTIVE", FUTURE, LocalTime.of(7, 0), false);
        unknownCityKnownCountry = trip("Yopougon-Village", "Paris", "CI", "FR", "ACTIVE", FUTURE, LocalTime.of(12, 0), false);
        cotonouCompleted = trip("Cotonou", "Paris", "BJ", "FR", "COMPLETED", FUTURE, LocalTime.of(10, 0), false);
        abidjanPast = trip("Abidjan", "Paris", "CI", "FR", "ACTIVE", PAST, LocalTime.of(10, 0), false);
        abidjanCancelled = trip("Abidjan", "Paris", "CI", "FR", "CANCELLED", FUTURE, LocalTime.of(10, 0), false);
        abidjanDeleted = trip("Abidjan", "Paris", "CI", "FR", "ACTIVE", FUTURE, LocalTime.of(10, 0), true);
        abidjanWithoutTime = trip("Abidjan", "Paris", "CI", "FR", "ACTIVE", FUTURE, null, false);

        flywayUpTo("300").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    // ---------------------------------------------------------------- V299

    @Test
    void v299_remplitLesCodesNuls_yComprisSurUnTrajetSupprime() throws Exception {
        assertThat(codes("announcements", deletedNoCodes)).isEqualTo("FR/SN");
    }

    @Test
    void v299_neReecritJamaisUnCodeRenseigne() throws Exception {
        assertThat(codes("announcements", partialCodes)).isEqualTo("BJ/CI");
        // Code faux mais pas l'inverse exact de la déduction : intact.
        assertThat(codes("announcements", wrongButNotSwapped)).isEqualTo("US/SN");
        assertThat(codes("trip_templates", templateWithCodes)).isEqualTo("SN/FR");
    }

    @Test
    void v299_corrigeLesCodesExactementIntervertis() throws Exception {
        assertThat(codes("announcements", swappedCodes)).isEqualTo("CI/FR");
    }

    @Test
    void v299_villeAbsenteDuReferentiel_laisseLeCodeNul_casseIgnoree() throws Exception {
        assertThat(codes("announcements", unknownDepartureCity)).isEqualTo("null/SN");
    }

    @Test
    void v299_rattrapeLesModelesDeTrajet() throws Exception {
        assertThat(codes("trip_templates", templateNoCodes)).isEqualTo("CI/FR");
    }

    // ---------------------------------------------------------------- V300

    @Test
    void v300_remplitLeFuseauDesVillesDepuisGeoNames() throws Exception {
        assertThat(single("SELECT timezone FROM cities WHERE id = 2394819")).isEqualTo("Africa/Porto-Novo");
        assertThat(single("SELECT timezone FROM cities WHERE id = 4699066")).isEqualTo("America/Chicago");
        assertThat(single("SELECT timezone FROM cities WHERE id = 999999999")).isNull();
    }

    @Test
    void v300_trajetsAVenir_fuseauDeLaVilleEtDepartRecalcule() throws Exception {
        assertTrip(abidjanUpcoming, "Africa/Abidjan", FUTURE.atTime(10, 0));
        assertTrip(houstonUpcoming, "America/Chicago", FUTURE.atTime(8, 0));
        // Instant de départ absent (trajet dédié) : posé.
        assertTrip(dakarWithoutDepartureAt, "Africa/Dakar", FUTURE.atTime(9, 0));
        // Ville inconnue, pays connu : fuseau principal du pays.
        assertTrip(unknownCityKnownCountry, "Africa/Abidjan", FUTURE.atTime(12, 0));
        // Départ de Paris : rien ne change.
        assertTrip(parisUpcoming, "Europe/Paris", FUTURE.atTime(7, 0));
    }

    @Test
    void v300_trajetsPassesTerminesAnnulesOuSupprimes_neBougentPas() throws Exception {
        for (UUID untouched : new UUID[]{cotonouCompleted, abidjanCancelled, abidjanDeleted}) {
            assertTrip(untouched, "Europe/Paris", FUTURE.atTime(10, 0));
        }
        assertTrip(abidjanPast, "Europe/Paris", PAST.atTime(10, 0));
    }

    @Test
    void v300_trajetSansHeure_fuseauSeulement() throws Exception {
        assertThat(single("SELECT timezone FROM announcements WHERE id = '" + abidjanWithoutTime + "'"))
                .isEqualTo("Africa/Abidjan");
        assertThat(single("SELECT departure_at::text FROM announcements WHERE id = '" + abidjanWithoutTime + "'"))
                .isNull();
    }

    // ---------------------------------------------------------------- outils

    private static void assertTrip(UUID id, String zone, java.time.LocalDateTime localDeparture) throws Exception {
        assertThat(single("SELECT timezone FROM announcements WHERE id = '" + id + "'")).isEqualTo(zone);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT departure_at FROM announcements WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            OffsetDateTime at = rs.getObject(1, OffsetDateTime.class);
            assertThat(at.toInstant()).isEqualTo(localDeparture.atZone(ZoneId.of(zone)).toInstant());
        }
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    private static void city(long id, String name, String country, long population) throws Exception {
        exec("INSERT INTO cities (id, name, country_code, country_name, population, latitude, longitude) "
                + "VALUES (" + id + ", '" + name + "', '" + country + "', '" + country + "', " + population
                + ", 0, 0)");
    }

    /** Trajet tel que l'écrivait l'ancien code : fuseau Europe/Paris, départ calculé à l'heure de Paris. */
    private static UUID trip(String from, String to, String fromCode, String toCode, String status,
                             LocalDate date, LocalTime time, boolean deleted) throws Exception {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_country_code, "
                + "arrival_country_code, departure_date, departure_time, departure_at, status, available_kg, "
                + "price_per_kg, transport_mode, total_kg, pickup_address_label, pickup_lat, pickup_lng, "
                + "delivery_address_label, delivery_lat, delivery_lng, created_at, updated_at, deleted_at) VALUES ("
                + "'" + id + "', '" + user + "', '" + from + "', '" + to + "', " + literal(fromCode) + ", "
                + literal(toCode) + ", DATE '" + date + "', "
                + (time == null ? "NULL, NULL" : "TIME '" + time + "', (DATE '" + date + "' + TIME '" + time
                        + "') AT TIME ZONE 'Europe/Paris'")
                + ", '" + status + "', 3.00, 15.00, 'PLANE', 3.00, 'Départ', 0, 0, 'Arrivée', 0, 0, now(), now(), "
                + (deleted ? "now()" : "NULL") + ")");
        return id;
    }

    private static UUID template(String from, String to, String fromCode, String toCode) throws Exception {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO trip_templates (id, user_id, label, departure_city, arrival_city, departure_country_code, "
                + "arrival_country_code, price_per_kg) VALUES ('" + id + "', '" + user + "', 'Modèle', '" + from
                + "', '" + to + "', " + literal(fromCode) + ", " + literal(toCode) + ", 8.0)");
        return id;
    }

    private static String literal(String value) {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static String codes(String table, UUID id) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT departure_country_code, arrival_country_code FROM " + table
                     + " WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1) + "/" + rs.getString(2);
        }
    }

    private static String single(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
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
