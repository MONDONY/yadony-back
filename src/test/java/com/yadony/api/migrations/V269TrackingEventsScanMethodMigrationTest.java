package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V269 : provenance d'une étape de suivi. Colonne nullable {@code scan_method} (les lignes
 * existantes restent NULL = inconnu) bornée par un CHECK à {@code QR} et {@code MANUAL}.
 */
class V269TrackingEventsScanMethodMigrationTest {

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

    private static String columnInfo(String table, String column) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT data_type, is_nullable, character_maximum_length "
                    + "FROM information_schema.columns WHERE table_name = '" + table
                    + "' AND column_name = '" + column + "'");
            return rs.next() ? rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) : null;
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void v269_ajouteScanMethodNullableBorneeAQrEtManual() throws Exception {
        Flyway baseline = flywayUpTo("268");
        baseline.clean();
        baseline.migrate();
        assertThat(columnInfo("tracking_events", "scan_method")).isNull();

        flywayUpTo("269").migrate();

        assertThat(columnInfo("tracking_events", "scan_method")).isEqualTo("character varying|YES|10");
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                    + "WHERE conname = 'tracking_events_scan_method_check'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("'QR'").contains("'MANUAL'");
        }

        // Le CHECK refuse une provenance inconnue (vérifié sans dépendre des FK de tracking_events).
        exec("CREATE TABLE scan_check_probe (LIKE tracking_events INCLUDING CONSTRAINTS)");
        exec("ALTER TABLE scan_check_probe ALTER COLUMN bid_id DROP NOT NULL");
        // Une ligne valide passe (QR, MANUAL, NULL) : l'échec ci-dessous vient donc bien du CHECK.
        for (String value : new String[]{"'QR'", "'MANUAL'", "NULL"}) {
            exec("INSERT INTO scan_check_probe (id, event_type, scanned_at, created_at, updated_at, scan_method) "
                    + "VALUES (gen_random_uuid(), 'DEPART', now(), now(), now(), " + value + ")");
        }
        assertThatThrownBy(() -> exec("INSERT INTO scan_check_probe (id, event_type, scanned_at, created_at, updated_at, scan_method) "
                + "VALUES (gen_random_uuid(), 'DEPART', now(), now(), now(), 'NFC')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("tracking_events_scan_method_check");
        exec("DROP TABLE scan_check_probe");
    }
}
