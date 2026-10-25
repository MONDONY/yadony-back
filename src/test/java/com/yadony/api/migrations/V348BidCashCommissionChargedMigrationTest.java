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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V348 : commission espèces réellement prélevée sur un colis ({@code bids.cash_commission_charged_amount}), nullable
 * pour les colis antérieurs. Joué sur un vrai PostgreSQL : le profil test (H2) n'exécute pas Flyway.
 */
class V348BidCashCommissionChargedMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("348").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    void colonneNullableEnNumeric14_2() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT data_type, numeric_precision, numeric_scale, is_nullable
                     FROM information_schema.columns
                     WHERE table_name = 'bids' AND column_name = 'cash_commission_charged_amount'""")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("numeric");
            assertThat(rs.getInt("numeric_precision")).isEqualTo(14);
            assertThat(rs.getInt("numeric_scale")).isEqualTo(2);
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");
        }
    }
}
