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

/** Brut scellé d'un fil de négociation à grille : colonne facultative, strictement positive. */
class V333NegotiationThreadSealedGrossMigrationTest {

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
    void migrate_addsNullablePositiveSealedGross() throws Exception {
        Flyway baseline = flywayUpTo("332");
        baseline.clean();
        baseline.migrate();
        flywayUpTo("333").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT data_type, numeric_precision, numeric_scale, is_nullable "
                    + "FROM information_schema.columns WHERE table_schema = 'public' "
                    + "AND table_name = 'negotiation_threads' AND column_name = 'sealed_gross_eur'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("numeric");
            assertThat(rs.getInt("numeric_precision")).isEqualTo(10);
            assertThat(rs.getInt("numeric_scale")).isEqualTo(2);
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");

            rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = 'chk_negotiation_threads_sealed_gross_positive'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("sealed_gross_eur IS NULL").contains("sealed_gross_eur > ");
        }
    }
}
