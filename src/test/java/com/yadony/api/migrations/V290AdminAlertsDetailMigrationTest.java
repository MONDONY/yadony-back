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
 * V290 : colonne {@code admin_alerts.detail} et rattrapage de {@code severity}, resté au
 * défaut INFO sur toutes les alertes levées avant cette version.
 */
class V290AdminAlertsDetailMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        migrateTo("289");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO admin_alerts (type, payload) VALUES
                      ('MONEY_INVARIANT_INV-05', '{"invariant":"INV-05","regle":"Argent versé au voyageur seulement si le colis est livré","gravite":"CRITIQUE","lignesEnFaute":2}'),
                      ('MONEY_INVARIANT_INV-14', '{"invariant":"INV-14","regle":"Aucune demande de remboursement wallet bloquée","gravite":"MOYENNE","lignesEnFaute":1}'),
                      ('ESCROW_J48_TIMEOUT', '{"paymentId":"p","bidId":"b","amount":"10"}'),
                      ('RETURN_DEADLINE_EXPIRED', '{"bidId":"b"}'),
                      ('PAWAPAY_BALANCE_LOW_XOF', '{"currency":"XOF"}')
                    """);
            statement.execute("INSERT INTO admin_alerts (type, severity) VALUES ('SUPPORT_TICKET_CREATED', 'WARN')");
        }
        migrateTo("290");
    }

    private static void migrateTo(String target) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target(target)
                .load()
                .migrate();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    private static String[] row(String type) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT severity, detail FROM admin_alerts WHERE type = ?")) {
            statement.setString(1, type);
            ResultSet rs = statement.executeQuery();
            assertThat(rs.next()).isTrue();
            return new String[] {rs.getString("severity"), rs.getString("detail")};
        }
    }

    @Test
    void addsANullableDetailColumn() throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT is_nullable, data_type FROM information_schema.columns
                     WHERE table_name = 'admin_alerts' AND column_name = 'detail'
                     """)) {
            ResultSet rs = statement.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("is_nullable")).isEqualTo("YES");
            assertThat(rs.getString("data_type")).isEqualTo("text");
        }
    }

    @Test
    void moneyInvariantAlertsTakeTheRuleSeverityAndAReadableDetail() throws Exception {
        assertThat(row("MONEY_INVARIANT_INV-05"))
                .containsExactly("CRITICAL", "Incohérence d'argent INV-05 : Argent versé au voyageur seulement si le colis "
                        + "est livré — 2 ligne(s) en faute");
        assertThat(row("MONEY_INVARIANT_INV-14")[0]).isEqualTo("WARN");
    }

    @Test
    void schedulerAlertsBecomeWarningsAndEscalatedAlertsCritical() throws Exception {
        assertThat(row("ESCROW_J48_TIMEOUT")[0]).isEqualTo("WARN");
        assertThat(row("RETURN_DEADLINE_EXPIRED")[0]).isEqualTo("WARN");
        assertThat(row("PAWAPAY_BALANCE_LOW_XOF")[0]).isEqualTo("CRITICAL");
    }

    @Test
    void anExplicitSeverityIsKept() throws Exception {
        assertThat(row("SUPPORT_TICKET_CREATED")[0]).isEqualTo("WARN");
    }
}
