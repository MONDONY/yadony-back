package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V331CommissionGridsMigrationTest {

    private static final String UTC_NOW = "(now() AT TIME ZONE 'UTC')";
    private static final String TIERS = "'[{\"fromEur\":\"0\",\"rate\":\"0.07\"},{\"fromEur\":\"100\",\"rate\":\"0.06\"}]'::jsonb";
    private static final String MINIMUMS = "'{\"EUR\":\"1.00\",\"XOF\":\"650\"}'::jsonb";

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

    private static void migrateTo330ThenFresh() {
        Flyway baseline = flywayUpTo("330");
        baseline.clean();
        baseline.migrate();
    }

    private static String columnType(Statement s, String table, String column) throws SQLException {
        ResultSet rs = s.executeQuery("SELECT data_type || coalesce('(' || numeric_precision || ',' || numeric_scale || ')', '') "
                + "FROM information_schema.columns WHERE table_schema = 'public' AND table_name = '" + table
                + "' AND column_name = '" + column + "'");
        assertThat(rs.next()).as("colonne %s.%s", table, column).isTrue();
        return rs.getString(1);
    }

    private static boolean hasForeignKeyToGrids(Statement s, String table, String column) throws SQLException {
        ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_constraint c "
                + "JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey) "
                + "WHERE c.contype = 'f' AND c.conrelid = '" + table + "'::regclass "
                + "AND c.confrelid = 'commission_grids'::regclass AND a.attname = '" + column + "'");
        rs.next();
        return rs.getInt(1) == 1;
    }

    private static UUID insertGrid(Statement s, String effectiveFrom) throws SQLException {
        UUID id = UUID.randomUUID();
        s.execute("INSERT INTO commission_grids (id, effective_from, tiers, minimums, created_by, created_at, updated_at) "
                + "VALUES ('" + id + "', " + effectiveFrom + ", " + TIERS + ", " + MINIMUMS + ", '" + UUID.randomUUID()
                + "', now(), now())");
        return id;
    }

    @Test
    void migrate_createsTableAndColumns() throws Exception {
        migrateTo330ThenFresh();
        flywayUpTo("331").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT column_name FROM information_schema.columns "
                    + "WHERE table_schema = 'public' AND table_name = 'commission_grids' ORDER BY column_name");
            List<String> columns = new ArrayList<>();
            while (rs.next()) columns.add(rs.getString(1));
            assertThat(columns).containsExactlyInAnyOrder("id", "effective_from", "tiers", "minimums", "note",
                    "created_by", "created_at", "updated_at", "deleted_at");

            assertThat(columnType(s, "commission_grids", "effective_from")).startsWith("timestamp without time zone");
            assertThat(columnType(s, "commission_grids", "tiers")).isEqualTo("jsonb");
            assertThat(columnType(s, "commission_grids", "minimums")).isEqualTo("jsonb");
            assertThat(columnType(s, "commission_grids", "created_by")).isEqualTo("uuid");

            assertThat(columnType(s, "bids", "commission_grid_id")).isEqualTo("uuid");
            assertThat(columnType(s, "bids", "commission_override_rate")).isEqualTo("numeric(7,6)");
            assertThat(columnType(s, "bids", "commission_voucher_factor")).isEqualTo("numeric(4,3)");
            assertThat(columnType(s, "package_requests", "commission_grid_id")).isEqualTo("uuid");
            assertThat(columnType(s, "negotiation_threads", "commission_grid_id")).isEqualTo("uuid");

            assertThat(columnType(s, "bids", "commission_rate")).isEqualTo("numeric(7,6)");
            assertThat(columnType(s, "negotiation_threads", "commission_rate")).isEqualTo("numeric(7,6)");
            assertThat(columnType(s, "promo_redemptions", "applied_rate")).isEqualTo("numeric(7,6)");

            assertThat(hasForeignKeyToGrids(s, "bids", "commission_grid_id")).isTrue();
            assertThat(hasForeignKeyToGrids(s, "package_requests", "commission_grid_id")).isTrue();
            assertThat(hasForeignKeyToGrids(s, "negotiation_threads", "commission_grid_id")).isTrue();

            rs = s.executeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_commission_grids_effective_from'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("(effective_from)").contains("deleted_at IS NULL");

            rs = s.executeQuery("SELECT count(*) FROM pg_constraint WHERE conname = 'bids_commission_rate_range'");
            rs.next();
            assertThat(rs.getInt(1)).as("le CHECK V118 reste en place").isEqualTo(1);
        }
    }

    @Test
    void trigger_rejectsUpdateOfEffectiveGrid() throws Exception {
        migrateTo330ThenFresh();
        flywayUpTo("331").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            UUID id = insertGrid(s, UTC_NOW + " - interval '1 minute'");
            assertThatThrownBy(() -> s.execute("UPDATE commission_grids SET tiers = '[]'::jsonb WHERE id = '" + id + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("commission_grid_immutable");
            assertThatThrownBy(() -> s.execute("UPDATE commission_grids SET deleted_at = now() WHERE id = '" + id + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("commission_grid_immutable");
            assertThatThrownBy(() -> s.execute("DELETE FROM commission_grids WHERE id = '" + id + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("commission_grid_immutable");
        }
    }

    @Test
    void trigger_allowsCancellingScheduledGrid() throws Exception {
        migrateTo330ThenFresh();
        flywayUpTo("331").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            UUID id = insertGrid(s, UTC_NOW + " + interval '1 day'");
            s.execute("UPDATE commission_grids SET deleted_at = now() WHERE id = '" + id + "'");
            ResultSet rs = s.executeQuery("SELECT deleted_at IS NOT NULL FROM commission_grids WHERE id = '" + id + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean(1)).isTrue();
            assertThatThrownBy(() -> s.execute("DELETE FROM commission_grids WHERE id = '" + id + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("commission_grid_immutable");
        }
    }

    @Test
    void existingRates_preserved() throws Exception {
        migrateTo330ThenFresh();
        UUID bidId = UUID.randomUUID();
        UUID threadId = UUID.randomUUID();
        UUID promoId = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            // Contraintes d'intégrité suspendues : seules les colonnes de taux comptent ici.
            s.execute("SET session_replication_role = replica");
            insertRow(s, "bids", bidId, "commission_rate", "0.050");
            insertRow(s, "negotiation_threads", threadId, "commission_rate", "0.120");
            s.execute("INSERT INTO promo_redemptions (id, promo_code_id, user_id, bid_id, applied_rate, redeemed_at) "
                    + "VALUES ('" + promoId + "', '" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', '"
                    + UUID.randomUUID() + "', 0.030, now())");
        }
        flywayUpTo("331").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            assertThat(rate(s, "bids", bidId)).isEqualByComparingTo("0.050000");
            assertThat(rate(s, "bids", bidId).toPlainString()).isEqualTo("0.050000");
            assertThat(rate(s, "negotiation_threads", threadId).toPlainString()).isEqualTo("0.120000");
            ResultSet rs = s.executeQuery("SELECT applied_rate FROM promo_redemptions WHERE id = '" + promoId + "'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBigDecimal(1).toPlainString()).isEqualTo("0.030000");

            // Précision élargie : un taux marginal de grille tient désormais en base.
            s.execute("UPDATE bids SET commission_rate = 0.066667 WHERE id = '" + bidId + "'");
            assertThat(rate(s, "bids", bidId).toPlainString()).isEqualTo("0.066667");
        }
    }

    /** Insère une ligne minimale : les colonnes NOT NULL sans défaut reçoivent une valeur neutre selon leur type. */
    private static void insertRow(Statement s, String table, UUID id, String rateColumn, String rate) throws SQLException {
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        ResultSet rs = s.executeQuery("SELECT column_name, data_type FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = '" + table
                + "' AND is_nullable = 'NO' AND column_default IS NULL ORDER BY ordinal_position");
        while (rs.next()) {
            String column = rs.getString(1);
            if (column.equals("id") || column.equals(rateColumn)) continue;
            columns.add(column);
            values.add(switch (rs.getString(2)) {
                case "uuid" -> "'" + UUID.randomUUID() + "'";
                case "character varying", "text" -> "'x'";
                case "integer", "bigint", "numeric", "smallint" -> "1";
                case "boolean" -> "false";
                case "date" -> "current_date";
                case "jsonb" -> "'{}'::jsonb";
                default -> "now()";
            });
        }
        columns.add("id");
        values.add("'" + id + "'");
        columns.add(rateColumn);
        values.add(rate);
        s.execute("INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES (" + String.join(", ", values) + ")");
    }

    private static BigDecimal rate(Statement s, String table, UUID id) throws SQLException {
        ResultSet rs = s.executeQuery("SELECT commission_rate FROM " + table + " WHERE id = '" + id + "'");
        assertThat(rs.next()).isTrue();
        return rs.getBigDecimal(1);
    }
}
