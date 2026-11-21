package com.yadony.api.auth;

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

/** V391 sur un vrai PostgreSQL : quatre colonnes nullables et l'index sur l'empreinte. */
class DeclaredPhoneSchemaPostgresIT {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("391").cleanDisabled(false).load().migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    void columnsAreNullableAndHashIsIndexed() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet cols = s.executeQuery("SELECT column_name, is_nullable FROM information_schema.columns "
                    + "WHERE table_name = 'users' AND column_name LIKE 'declared_phone%'");
            int n = 0;
            while (cols.next()) {
                assertThat(cols.getString("is_nullable")).isEqualTo("YES");
                n++;
            }
            assertThat(n).isEqualTo(4);
            ResultSet idx = s.executeQuery("SELECT count(*) FROM pg_indexes WHERE tablename = 'users' "
                    + "AND indexname = 'idx_users_declared_phone_hash'");
            idx.next();
            assertThat(idx.getInt(1)).isEqualTo(1);
        }
    }
}
