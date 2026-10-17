package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V328 : la date de départ (et sa tolérance) d'une demande de colis devient facultative
 * (« Date flexible »). La borne 0..7 de la tolérance reste appliquée quand elle est donnée.
 */
class V328PackageRequestDateFacultativeMigrationTest {

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

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    @Test
    void v328_rendLaDateEtLaToleranceFacultatives() throws Exception {
        Flyway baseline = flywayUpTo("326");
        baseline.clean();
        baseline.migrate();
        UUID sender = seedUser();

        assertThatThrownBy(() -> seedRequest(sender, null, null))
                .isInstanceOf(SQLException.class).hasMessageContaining("desired_date");

        flywayUpTo("328").migrate();

        UUID flexible = seedRequest(sender, null, null);
        assertThat(flexible).isNotNull();
        // Une demande datée garde la tolérance par défaut de V57 quand elle n'est pas fournie.
        UUID dated = seedRequestWithDefaultTolerance(sender);
        assertThat(toleranceOf(dated)).isEqualTo(2);
        assertThatThrownBy(() -> seedRequest(sender, "CURRENT_DATE + 10", 9))
                .isInstanceOf(SQLException.class).hasMessageContaining("chk_pkg_req_tolerance");
    }

    private static UUID seedUser() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users (id, firebase_uid, username) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "uid-" + id);
            ps.setString(3, ("u" + id.toString().replace("-", "")).substring(0, 20));
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedRequest(UUID sender, String dateSql, Integer tolerance) throws SQLException {
        UUID id = UUID.randomUUID();
        String date = dateSql == null ? "NULL" : dateSql;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date,"
                     + " date_tolerance_days, weight_kg, parcel_size, content_category, transport_mode, status)"
                     + " VALUES (?, ?, 'Paris', 'Dakar', " + date + ", ?, 5, 'SMALL', 'CLOTHES', 'PLANE', 'OPEN')")) {
            ps.setObject(1, id);
            ps.setObject(2, sender);
            ps.setObject(3, tolerance, java.sql.Types.SMALLINT);
            ps.executeUpdate();
        }
        return id;
    }

    private static UUID seedRequestWithDefaultTolerance(UUID sender) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO package_requests (id, sender_id, departure_city, arrival_city, desired_date,
                         weight_kg, parcel_size, content_category, transport_mode, status)
                     VALUES (?, ?, 'Paris', 'Dakar', CURRENT_DATE + 10, 5, 'SMALL', 'CLOTHES', 'PLANE', 'OPEN')
                     """)) {
            ps.setObject(1, id);
            ps.setObject(2, sender);
            ps.executeUpdate();
        }
        return id;
    }

    private static int toleranceOf(UUID id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT date_tolerance_days FROM package_requests WHERE id = ?")) {
            ps.setObject(1, id);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
