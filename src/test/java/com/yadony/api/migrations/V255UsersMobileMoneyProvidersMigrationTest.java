package com.yadony.api.migrations;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * V255 : colonne {@code users.mobile_money_providers} (réseaux acceptés, CSV) rétro-remplie avec
 * l'opérateur unique des comptes existants. Le profil test tourne sur H2 sans Flyway : on migre un
 * PostgreSQL embarqué jusqu'à V254, on sème, on applique V255 et on vérifie sur le vrai moteur.
 */
class V255UsersMobileMoneyProvidersMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void resetSchemaUpToV254() {
        Flyway upTo = flywayUpTo("254");
        upTo.clean();
        upTo.migrate();
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .target(targetVersion)
                .cleanDisabled(false)
                .load();
    }

    private UUID seedUser(String provider) throws SQLException {
        UUID id = UUID.randomUUID();
        String providerSql = provider == null ? "NULL" : "'" + provider + "'";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, firebase_uid, username, status, mobile_money_provider, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'ACTIVE', %s, now(), now())
                    """.formatted(id, "uid-" + id.toString().substring(0, 8), "user-" + id.toString().substring(0, 8), providerSql));
        }
        return id;
    }

    private String providersOf(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT mobile_money_providers FROM users WHERE id = '" + userId + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    @Test
    void afterV255_anExistingProvider_isCopiedIntoTheProvidersColumn() throws Exception {
        UUID id = seedUser("ORANGE_SEN");
        flywayUpTo("255").migrate();
        assertThat(providersOf(id)).isEqualTo("ORANGE_SEN");
    }

    @Test
    void afterV255_aUserWithoutProvider_keepsANullProvidersColumn() throws Exception {
        UUID id = seedUser(null);
        flywayUpTo("255").migrate();
        assertThat(providersOf(id)).isNull();
    }
}
