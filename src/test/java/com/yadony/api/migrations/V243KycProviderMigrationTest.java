package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V243 : la colonne de session cesse d'être propre à Stripe et chaque ligne porte le
 * fournisseur qui l'a produite. Tout l'historique vient de Stripe Identity, d'où le
 * rétro-remplissage.
 */
class V243KycProviderMigrationTest {

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
    void resetSchema() {
        Flyway baseline = flywayUpTo("240");
        baseline.clean();
        baseline.migrate();
    }

    @Test
    void renamesSessionColumn_andBackfillsProviderToStripe() throws Exception {
        UUID userId;
        try (Connection connection = dataSource.getConnection()) {
            userId = seedUser(connection);
            // Ligne écrite AVANT la migration, donc avec l'ancien nom de colonne.
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO kyc_schema.kyc_verifications
                        (id, user_id, stripe_verification_session_id, status, created_at, updated_at)
                    VALUES (?, ?, 'vs_legacy_001', 'VERIFIED', now(), now())
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, userId);
                statement.executeUpdate();
            }
        }

        flywayUpTo("243").migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT provider, verification_session_id
                     FROM kyc_schema.kyc_verifications
                     WHERE verification_session_id = 'vs_legacy_001'
                     """)) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString("provider")).isEqualTo("STRIPE");
            assertThat(rows.getString("verification_session_id")).isEqualTo("vs_legacy_001");
        }
    }

    @Test
    void dropsTheStripeSpecificColumnName() throws Exception {
        flywayUpTo("243").migrate();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT column_name FROM information_schema.columns
                     WHERE table_schema = 'kyc_schema' AND table_name = 'kyc_verifications'
                       AND column_name = 'stripe_verification_session_id'
                     """)) {
            assertThat(rows.next()).isFalse();
        }
    }

    @Test
    void newRowsDefaultToStripe_whenProviderIsNotProvided() throws Exception {
        flywayUpTo("243").migrate();

        try (Connection connection = dataSource.getConnection()) {
            UUID userId = seedUser(connection);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO kyc_schema.kyc_verifications
                        (id, user_id, verification_session_id, status, created_at, updated_at)
                    VALUES (?, ?, 'vs_new_001', 'PENDING', now(), now())
                    """)) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, userId);
                statement.executeUpdate();
            }

            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT provider FROM kyc_schema.kyc_verifications
                         WHERE verification_session_id = 'vs_new_001'
                         """)) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString("provider")).isEqualTo("STRIPE");
            }
        }
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

    private UUID seedUser(Connection connection) throws Exception {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at)
                VALUES (?, ?, ?, 'ACTIVE', now(), now())
                """)) {
            statement.setObject(1, id);
            statement.setString(2, "uid_" + id);
            statement.setString(3, "user_" + id.toString().substring(0, 8));
            statement.executeUpdate();
        }
        return id;
    }
}
