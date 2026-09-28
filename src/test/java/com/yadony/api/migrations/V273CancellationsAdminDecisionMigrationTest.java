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
 * V273 : marqueur de décision admin sur une déclaration de no-show. Quatre colonnes
 * nullables (les lignes existantes restent sans décision), valeur bornée à CONFIRMED /
 * REJECTED, et une décision n'existe jamais sans son auteur, sa date et son motif.
 */
class V273CancellationsAdminDecisionMigrationTest {

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

    private static String columnInfo(String column) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT data_type, is_nullable, character_maximum_length "
                    + "FROM information_schema.columns WHERE table_name = 'cancellations' AND column_name = '"
                    + column + "'");
            return rs.next() ? rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3) : null;
        }
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void v273_ajouteLaDecisionAdminNullableEtCoherente() throws Exception {
        Flyway baseline = flywayUpTo("272");
        baseline.clean();
        baseline.migrate();
        assertThat(columnInfo("admin_decision")).isNull();

        flywayUpTo("273").migrate();

        assertThat(columnInfo("admin_decision")).isEqualTo("character varying|YES|10");
        assertThat(columnInfo("decided_by_admin_id")).isEqualTo("uuid|YES|null");
        assertThat(columnInfo("decided_at")).isEqualTo("timestamp with time zone|YES|null");
        assertThat(columnInfo("decision_reason")).isEqualTo("text|YES|null");

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT indexdef FROM pg_indexes "
                    + "WHERE indexname = 'idx_cancellations_noshow_admin_queue'");
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("SENDER_NO_SHOW").contains("RECIPIENT_NO_SHOW")
                    .contains("TRAVELER_DELIVERY_NO_SHOW");
        }

        // Contraintes vérifiées sur une copie, sans dépendre des FK bids/users.
        exec("CREATE TABLE decision_probe (LIKE cancellations INCLUDING CONSTRAINTS INCLUDING DEFAULTS)");
        String base = "INSERT INTO decision_probe (id, bid_id, cancelled_by, reason%s) VALUES "
                + "(gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'SENDER_NO_SHOW'%s)";
        exec(String.format(base, "", ""));
        exec(String.format(base, ", admin_decision, decided_by_admin_id, decided_at, decision_reason",
                ", 'REJECTED', gen_random_uuid(), now(), 'Remise prouvée par photo'"));
        assertThatThrownBy(() -> exec(String.format(base,
                ", admin_decision, decided_by_admin_id, decided_at, decision_reason",
                ", 'MAYBE', gen_random_uuid(), now(), 'Motif quelconque'")))
                .hasMessageContaining("cancellations_admin_decision_check");
        assertThatThrownBy(() -> exec(String.format(base, ", admin_decision", ", 'CONFIRMED'")))
                .hasMessageContaining("cancellations_admin_decision_complete_check");
        exec("DROP TABLE decision_probe");
    }
}
