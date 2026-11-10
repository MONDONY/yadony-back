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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V370 : limite mensuelle de trajets par utilisateur (NULL = aucune limite, 1 à 100 sinon) et champs
 * entreprise (numéro, pays) des demandes GP Pro.
 */
class V370MonthlyTripLimitMigrationTest {

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
    void v370_limiteNulleParDefaut_bornee1a100_etChampsEntrepriseDeLaDemande() throws Exception {
        Flyway baseline = flywayUpTo("369");
        baseline.clean();
        baseline.migrate();
        UUID existing = seedUser();

        flywayUpTo("370").migrate();

        // Aucune valeur par défaut : les comptes existants n'ont pas de limite.
        assertThat(limitOf(existing)).isNull();

        // Hors bornes : refusé.
        assertThatThrownBy(() -> setLimit(existing, 0)).isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_users_monthly_trip_limit");
        assertThatThrownBy(() -> setLimit(existing, 101)).isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_users_monthly_trip_limit");
        assertThatThrownBy(() -> setLimit(existing, -3)).isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_users_monthly_trip_limit");

        // Bornes incluses et retour à « aucune limite ».
        assertThatCode(() -> setLimit(existing, 1)).doesNotThrowAnyException();
        assertThat(limitOf(existing)).isEqualTo(1);
        assertThatCode(() -> setLimit(existing, 100)).doesNotThrowAnyException();
        assertThat(limitOf(existing)).isEqualTo(100);
        assertThatCode(() -> setLimit(existing, null)).doesNotThrowAnyException();
        assertThat(limitOf(existing)).isNull();

        // Les demandes GP Pro gagnent le numéro et le pays, nullables.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT column_name, is_nullable, character_maximum_length "
                     + "FROM information_schema.columns WHERE table_name = 'gp_pro_applications' "
                     + "AND column_name IN ('business_registration_number', 'business_country') "
                     + "ORDER BY column_name")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("column_name")).isEqualTo("business_country");
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            assertThat(rs.getInt("character_maximum_length")).isEqualTo(2);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("column_name")).isEqualTo("business_registration_number");
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            assertThat(rs.getInt("character_maximum_length")).isEqualTo(255);
            assertThat(rs.next()).isFalse();
        }
    }

    private static UUID seedUser() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("INSERT INTO users (firebase_uid, username) VALUES ('v370-" + suffix
                     + "', 'v370_" + suffix + "') RETURNING id")) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }

    private static void setLimit(UUID id, Integer limit) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE users SET monthly_trip_limit = ? WHERE id = ?")) {
            ps.setObject(1, limit);
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    private static Integer limitOf(UUID id) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT monthly_trip_limit FROM users WHERE id = ?")) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            int v = rs.getInt(1);
            return rs.wasNull() ? null : v;
        }
    }
}
