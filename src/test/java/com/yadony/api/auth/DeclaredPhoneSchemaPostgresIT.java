package com.yadony.api.auth;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V392 et V393 sur un vrai PostgreSQL : quatre colonnes nullables, l'index sur l'empreinte, puis l'index
 * unique partiel qui n'admet qu'un compte vivant validé par Yadony par numéro (H2 ne joue pas les migrations).
 */
class DeclaredPhoneSchemaPostgresIT {

    private static final String HASH = "a".repeat(64);

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
                .schemas("public", "kyc_schema").target("393").cleanDisabled(false).load().migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @BeforeEach
    void clean() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE users SET declared_phone_hash = NULL, declared_phone_level = NULL "
                    + "WHERE declared_phone_hash = '" + HASH + "'");
        }
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

    @Test
    void v393_uniquePartialIndexOnYadonyVerifiedHash() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet idx = s.executeQuery("SELECT indexdef FROM pg_indexes WHERE tablename = 'users' "
                    + "AND indexname = 'uq_users_declared_phone_yadony'");
            assertThat(idx.next()).as("index uq_users_declared_phone_yadony créé par V393").isTrue();
            assertThat(idx.getString(1)).startsWith("CREATE UNIQUE INDEX")
                    .contains("declared_phone_hash")
                    .contains("YADONY_VERIFIED")
                    .contains("deleted_at IS NULL");
        }
    }

    @Test
    void v393_secondLiveYadonyAccountOnSameHash_isRejected() throws Exception {
        UUID a = insertUser("YADONY_VERIFIED", false);
        UUID b = insertUser("DECLARED", false);

        assertThatThrownBy(() -> setLevel(b, "YADONY_VERIFIED"))
                .isInstanceOfSatisfying(SQLException.class, e -> {
                    assertThat(e.getSQLState()).isEqualTo("23505");
                    assertThat(e.getMessage()).contains("uq_users_declared_phone_yadony");
                });
        assertThat(a).isNotNull();
    }

    @Test
    void v393_plainDeclarantsAndDeletedYadonyAccounts_doNotBlock() throws Exception {
        insertUser("DECLARED", false);
        insertUser("DECLARED", false);
        insertUser("YADONY_VERIFIED", true);

        UUID live = insertUser("DECLARED", false);
        setLevel(live, "YADONY_VERIFIED");
    }

    private static UUID insertUser(String level, boolean deleted) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, firebase_uid, username, deleted_at, declared_phone_hash, declared_phone_level) "
                        + "VALUES (?, ?, ?, " + (deleted ? "now()" : "NULL") + ", ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, "uid-" + id);
            ps.setString(3, "u" + id.toString().replace("-", "").substring(0, 20));
            ps.setString(4, HASH);
            ps.setString(5, level);
            ps.executeUpdate();
        }
        return id;
    }

    private static void setLevel(UUID id, String level) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE users SET declared_phone_level = ? WHERE id = ?")) {
            ps.setString(1, level);
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }
}
