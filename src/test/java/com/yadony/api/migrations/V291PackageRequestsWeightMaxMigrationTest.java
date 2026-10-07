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
 * V291 : la contrainte {@code chk_pkg_req_weight} borne le poids à 32 kg, comme
 * {@code PackageRequestCreateRequest} (@DecimalMax("32.0")) — STAGING-M.
 */
class V291PackageRequestsWeightMaxMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target("291")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void weightConstraintAcceptsUpTo32Kg() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT pg_get_constraintdef(oid) FROM pg_constraint
                     WHERE conname = 'chk_pkg_req_weight'
                       AND conrelid = to_regclass('public.package_requests')
                     """)) {
            ResultSet rs = statement.executeQuery();
            assertThat(rs.next()).isTrue();
            String definition = rs.getString(1);
            assertThat(definition).contains("weight_kg").contains("0.5").contains("32");
            assertThat(definition).doesNotContain("30");
            assertThat(rs.next()).isFalse();
        }
    }
}
