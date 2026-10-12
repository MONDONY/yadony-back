package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Date;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V314 : les mois des alertes deviennent des périodes, les clics de visiteurs n'ont pas de propriétaire. */
class V314FlightAlertPeriodsMigrationTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static final UUID MONTH_ALERT = UUID.randomUUID();
    private static final UUID ANY_DATE_ALERT = UUID.randomUUID();

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway at313 = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("313").cleanDisabled(false).load();
        at313.clean();
        at313.migrate();
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                dataSource.getConnection(), true));

        // Le propriétaire n'a pas de ligne users ici : sans cette clé étrangère, les UPDATE de V314 sur
        // la même ligne (deux mises à jour dans la transaction) re-vérifieraient une référence absente.
        jdbc.execute("ALTER TABLE flight_price_alerts DROP CONSTRAINT flight_price_alerts_owner_id_fkey");
        // Des alertes existantes avant V314 : un mois précis (février bissextile) et « toutes les dates ».
        jdbc.update("INSERT INTO flight_price_alerts (id, owner_id, origin_code, destination_code, departure_month, "
                + "return_month, max_price, currency) VALUES (?, ?, 'PAR', 'ABJ', '2028-02', '2028-12', 300, 'EUR')",
                MONTH_ALERT, UUID.randomUUID());
        jdbc.update("INSERT INTO flight_price_alerts (id, owner_id, origin_code, destination_code, max_price, "
                + "currency) VALUES (?, ?, 'PAR', 'DKR', 300, 'EUR')", ANY_DATE_ALERT, UUID.randomUUID());

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("314").load().migrate();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    void existingMonths_becomeFirstAndLastDay() {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM flight_price_alerts WHERE id = ?", MONTH_ALERT);

        assertThat(row.get("departure_from")).isEqualTo(Date.valueOf("2028-02-01"));
        assertThat(row.get("departure_to")).isEqualTo(Date.valueOf("2028-02-29"));
        assertThat(row.get("return_from")).isEqualTo(Date.valueOf("2028-12-01"));
        assertThat(row.get("return_to")).isEqualTo(Date.valueOf("2028-12-31"));
        assertThat(row).doesNotContainKeys("departure_month", "return_month");
    }

    @Test
    void alertsWithoutDates_stayOpen() {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM flight_price_alerts WHERE id = ?", ANY_DATE_ALERT);

        assertThat(row.get("departure_from")).isNull();
        assertThat(row.get("departure_to")).isNull();
        assertThat(row.get("return_from")).isNull();
    }

    @Test
    void periodBounds_mustComeInPairs_andBeOrdered() {
        assertThatThrownBy(() -> insertAlert("DATE '2030-01-10'", "NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAlert("DATE '2030-01-10'", "DATE '2030-01-09'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insertAlert("DATE '2030-01-10'", "DATE '2030-01-10'");
    }

    private static void insertAlert(String from, String to) {
        jdbc.update("INSERT INTO flight_price_alerts (owner_id, origin_code, destination_code, departure_from, "
                + "departure_to, max_price, currency) VALUES (?, 'PAR', 'ABJ', " + from + ", " + to + ", 100, 'EUR')",
                UUID.randomUUID());
    }

    @Test
    void guestClicks_haveNoOwner() {
        jdbc.update("INSERT INTO flight_clicks (owner_id, origin_code, destination_code, source, sub_id) "
                + "VALUES (NULL, 'PAR', 'ABJ', 'search', 'guestclick01')");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM flight_clicks WHERE owner_id IS NULL", Integer.class))
                .isEqualTo(1);
    }
}
