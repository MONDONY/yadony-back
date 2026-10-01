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
 * V278 : table des reports de trajet, compteur borné à 2 sur l'annonce, report en attente
 * de réponse sur le bid.
 */
class V278TripReschedulesMigrationTest {

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
    void v278_creeLesReportsEtBorneLeCompteur() throws Exception {
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("278").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();

        assertThat(column("trip_reschedules", "new_departure_date")).isEqualTo("NO");
        assertThat(column("bids", "pending_reschedule_id")).isEqualTo("YES");
        assertThat(column("announcements", "reschedule_count")).isEqualTo("NO");
        assertThat(constraint("chk_announcements_reschedule_count")).contains(">= 0").contains("<= 2");
        assertThat(constraint("chk_trip_reschedules_reason"))
                .contains("FLIGHT_CANCELLED").contains("POSTPONED").contains("OTHER");
    }

    private static String column(String table, String column) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT is_nullable FROM information_schema.columns "
                     + "WHERE table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).as(table + "." + column).isTrue();
            return rs.getString(1);
        }
    }

    private static String constraint(String name) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            ps.setString(1, name);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).as(name).isTrue();
            return rs.getString(1);
        }
    }
}
