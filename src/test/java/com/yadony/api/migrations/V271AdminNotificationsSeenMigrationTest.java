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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V271 : date de dernière consultation de la cloche par administrateur
 * ({@code admin_users.notifications_seen_at}, nullable) et index des sources du fil, chacune
 * interrogée par date décroissante toutes les 15 secondes au plus.
 */
class V271AdminNotificationsSeenMigrationTest {

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
            ResultSet rs = s.executeQuery("SELECT data_type, is_nullable FROM information_schema.columns "
                    + "WHERE table_name = '" + table + "' AND column_name = '" + column + "'");
            return rs.next() ? rs.getString(1) + "|" + rs.getString(2) : null;
        }
    }

    private static List<String> indexes() throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT indexname FROM pg_indexes WHERE indexname LIKE 'idx\\_notif\\_%'");
            while (rs.next()) names.add(rs.getString(1));
        }
        return names;
    }

    @Test
    void v271_ajouteLaDateDeConsultation_etLesIndexDesSources() throws Exception {
        Flyway baseline = flywayUpTo("270");
        baseline.clean();
        baseline.migrate();
        assertThat(columnInfo("admin_users", "notifications_seen_at")).isNull();

        flywayUpTo("271").migrate();

        assertThat(columnInfo("admin_users", "notifications_seen_at"))
                .isEqualTo("timestamp without time zone|YES");
        assertThat(indexes()).containsExactlyInAnyOrder(
                "idx_notif_reports_created",
                "idx_notif_support_tickets_created",
                "idx_notif_support_messages_user",
                "idx_notif_disputes_created",
                "idx_notif_cancellations_noshow",
                "idx_notif_kyc_submitted",
                "idx_notif_wallet_refunds_manual",
                "idx_notif_users_deletion",
                "idx_notif_admin_alerts_open");
    }
}
