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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V272 : un signalement de message porte l'identifiant Firestore de son message. La colonne
 * est nullable (les signalements existants la gardent vide) ; action_taken, sans CHECK,
 * accepte les nouvelles actions sans migration.
 */
class V272ReportsTargetMessageIdMigrationTest {

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
    void v272_ajouteLIdentifiantDuMessageNullable() throws Exception {
        Flyway baseline = flywayUpTo("271");
        baseline.clean();
        baseline.migrate();
        UUID ancien = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO reports (id, target_type, target_id, reason) VALUES (?, 'MESSAGE', ?, 'SPAM')")) {
            ps.setObject(1, ancien);
            ps.setObject(2, UUID.randomUUID());
            ps.executeUpdate();
        }

        flywayUpTo("272").migrate();

        UUID nouveau = UUID.randomUUID();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO reports (id, target_type, target_id, reason, target_message_id, status, action_taken) "
                             + "VALUES (?, 'MESSAGE', ?, 'SPAM', 'AbCdEfGhIjKlMnOpQrSt', 'RESOLVED', 'SUSPEND_AUTHOR')")) {
            ps.setObject(1, nouveau);
            ps.setObject(2, UUID.randomUUID());
            ps.executeUpdate();
        }
        assertThat(messageId(ancien)).isNull();
        assertThat(messageId(nouveau)).isEqualTo("AbCdEfGhIjKlMnOpQrSt");
    }

    private static String messageId(UUID id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT target_message_id FROM reports WHERE id = ?")) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
