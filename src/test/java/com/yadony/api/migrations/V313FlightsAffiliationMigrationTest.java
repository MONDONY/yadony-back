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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V313 : tables des billets d'avion en affiliation, contraintes de format et d'unicité. */
class V313FlightsAffiliationMigrationTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("313").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                dataSource.getConnection(), true));
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    /** Les clés étrangères vers users sont hors sujet ici : désactivées le temps de l'insertion. */
    private static void withoutForeignKeys(Runnable insert) {
        jdbc.execute("SET session_replication_role = replica");
        try {
            insert.run();
        } finally {
            jdbc.execute("SET session_replication_role = DEFAULT");
        }
    }

    private static void insertAlert(String departureMonth, String maxPrice) {
        withoutForeignKeys(() -> jdbc.update(
                "INSERT INTO flight_price_alerts (owner_id, origin_code, destination_code, departure_month, "
                        + "max_price, currency) VALUES (?, 'PAR', 'ABJ', ?, " + maxPrice + ", 'EUR')",
                UUID.randomUUID(), departureMonth));
    }

    @Test
    void tablesExist() {
        for (String table : new String[]{"flight_searches", "flight_city_codes", "flight_price_alerts", "flight_clicks"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = ?", Integer.class, table);
            assertThat(count).as(table).isEqualTo(1);
        }
    }

    @Test
    void alert_defaults_andValidMonthAccepted() {
        insertAlert("2026-12", "400");

        Boolean active = jdbc.queryForObject(
                "SELECT active FROM flight_price_alerts WHERE departure_month = '2026-12' LIMIT 1", Boolean.class);
        assertThat(active).isTrue();
    }

    @Test
    void alert_malformedMonthOrNonPositivePrice_rejected() {
        assertThatThrownBy(() -> insertAlert("12/2026", "400")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAlert(null, "0")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void cityKey_isUnique() {
        jdbc.update("INSERT INTO flight_city_codes (city_key, city_name, iata_code) VALUES ('abidjan', 'Abidjan', 'ABJ')");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO flight_city_codes (city_key, city_name, iata_code) VALUES ('abidjan', 'ABIDJAN', 'ABJ')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void click_sourceAndSubIdConstraints() {
        withoutForeignKeys(() -> jdbc.update("INSERT INTO flight_clicks (owner_id, origin_code, destination_code, "
                + "source, sub_id) VALUES (?, 'PAR', 'ABJ', 'deal', 'abc123')", UUID.randomUUID()));

        assertThatThrownBy(() -> withoutForeignKeys(() -> jdbc.update("INSERT INTO flight_clicks (owner_id, "
                + "origin_code, destination_code, source, sub_id) VALUES (?, 'PAR', 'ABJ', 'deal', 'abc123')",
                UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> withoutForeignKeys(() -> jdbc.update("INSERT INTO flight_clicks (owner_id, "
                + "origin_code, destination_code, source, sub_id) VALUES (?, 'PAR', 'ABJ', 'ads', 'zzz999')",
                UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void ownerMustExist_whenForeignKeysAreOn() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO flight_searches (owner_id, origin_code, destination_code) "
                + "VALUES (?, 'PAR', 'ABJ')", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
