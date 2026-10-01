package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V277 : modèles et récurrences mémorisent le jour d'arrivée relatif au départ.
 * Colonne NOT NULL à 0 par défaut (les modèles existants restent « même jour »),
 * bornée à 3 comme chk_announcements_arrival_date.
 */
class V277TripTemplatesArrivalDayOffsetMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    void v277_ajouteLeJourDArriveeBorneSurModelesEtRecurrences() throws Exception {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("277").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();

        for (String table : new String[] {"trip_templates", "trip_recurrences"}) {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT is_nullable, column_default FROM information_schema.columns "
                                 + "WHERE table_name = ? AND column_name = 'arrival_day_offset'")) {
                ps.setString(1, table);
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as(table).isTrue();
                assertThat(rs.getString(1)).isEqualTo("NO");
                assertThat(rs.getString(2)).isEqualTo("0");
            }
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                         "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
                ps.setString(1, "chk_" + table + "_arrival_day_offset");
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).as(table).isTrue();
                assertThat(rs.getString(1)).contains(">= 0").contains("<= 3");
            }
        }
    }
}
