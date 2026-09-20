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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V263 : rapports envoyés depuis le scarabée d'un écran. Le CHECK de V262 doit
 * accepter SCREEN_BUG, et la colonne screen_route apparaît, nullable.
 */
class V263ReportsScreenRouteMigrationTest {

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
    void v263_accepteScreenBugEtAjouteLaRoute() throws Exception {
        Flyway baseline = flywayUpTo("262");
        baseline.clean();
        baseline.migrate();
        assertThatThrownBy(() -> seed("SCREEN_BUG", null))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_reports_reason");

        flywayUpTo("263").migrate();

        UUID id = seed("SCREEN_BUG", "/profile");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT reason, screen_route FROM reports WHERE id = ?")) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("reason")).isEqualTo("SCREEN_BUG");
            assertThat(rs.getString("screen_route")).isEqualTo("/profile");
        }
        // Les anciens codes passent toujours, sans route.
        seed("APP_BUG", null);
        seed("OTHER", null);
    }

    private static UUID seed(String reason, String screenRoute) throws SQLException {
        UUID id = UUID.randomUUID();
        boolean withRoute = screenRoute != null;
        String sql = withRoute
                ? "INSERT INTO reports (id, target_type, reason, screen_route) VALUES (?, ?, ?, ?)"
                : "INSERT INTO reports (id, target_type, reason) VALUES (?, ?, ?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.setString(2, "APP");
            ps.setString(3, reason);
            if (withRoute) ps.setString(4, screenRoute);
            ps.executeUpdate();
        }
        return id;
    }
}
