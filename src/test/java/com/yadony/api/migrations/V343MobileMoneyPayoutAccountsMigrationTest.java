package com.yadony.api.migrations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * V343 : table {@code mobile_money_payout_accounts} (un compte par utilisateur et par code opérateur
 * pawaPay), reprise du compte unique existant et colonne {@code users.mobile_money_currencies}.
 * Le profil test tourne sur H2 sans Flyway : on migre un PostgreSQL embarqué jusqu'à V342, on sème,
 * on applique V343 et on vérifie sur le vrai moteur.
 */
class V343MobileMoneyPayoutAccountsMigrationTest {

    private static final String CIPHERTEXT = "ZW5jcnlwdGVkLW1zaXNkbi1jaXBoZXJ0ZXh0LWFlcy1nY20=";

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
    void resetSchemaUpToV342() {
        Flyway upTo = flywayUpTo("342");
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

    private UUID seedUser(String status, String msisdn, String providers, boolean deleted) throws SQLException {
        UUID id = UUID.randomUUID();
        String msisdnSql = msisdn == null ? "NULL" : "'" + msisdn + "'";
        String maskedSql = msisdn == null ? "NULL" : "'+225 ** ** 12'";
        String providerSql = msisdn == null ? "NULL" : "'ORANGE_CIV'";
        String countrySql = msisdn == null ? "NULL" : "'CI'";
        String currencySql = msisdn == null ? "NULL" : "'XOF'";
        String providersSql = providers == null ? "NULL" : "'" + providers + "'";
        String deletedSql = deleted ? "now()" : "NULL";
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, firebase_uid, username, status, mobile_money_status, mobile_money_msisdn,
                        mobile_money_msisdn_masked, mobile_money_provider, mobile_money_country, mobile_money_currency,
                        mobile_money_providers, mobile_money_verified_at, deleted_at, created_at, updated_at)
                    VALUES ('%s', '%s', '%s', 'ACTIVE', '%s', %s, %s, %s, %s, %s, %s, %s, %s, now(), now())
                    """.formatted(id, "uid-" + id.toString().substring(0, 8), "user-" + id.toString().substring(0, 8),
                    status, msisdnSql, maskedSql, providerSql, countrySql, currencySql, providersSql,
                    msisdn == null ? "NULL" : "'2026-03-04 10:11:12+00'", deletedSql));
        }
        return id;
    }

    private long countAccounts() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT count(*) FROM mobile_money_payout_accounts")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private String currenciesOf(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT mobile_money_currencies FROM users WHERE id = '" + userId + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private String statusOf(UUID userId) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT mobile_money_status FROM users WHERE id = '" + userId + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private void insertAccount(UUID userId, String provider, boolean deleted) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO mobile_money_payout_accounts (id, user_id, provider, country, currency, msisdn,
                        msisdn_masked, verified_at, created_at, updated_at, deleted_at)
                    VALUES ('%s', '%s', '%s', 'CI', 'XOF', 'cipher', '+225 ** ** 99', now(), now(), now(), %s)
                    """.formatted(UUID.randomUUID(), userId, provider, deleted ? "now()" : "NULL"));
        }
    }

    @Test
    void afterV343_onlyTheActiveLiveAccountWithAllFieldsIsMigrated() throws Exception {
        UUID a = seedUser("ACTIVE", CIPHERTEXT, "ORANGE_CIV,MTN_MOMO_CIV", false);
        UUID b = seedUser("DISABLED", CIPHERTEXT, "ORANGE_CIV", false);
        UUID c = seedUser("ACTIVE", null, null, false);
        UUID d = seedUser("ACTIVE", CIPHERTEXT, "ORANGE_CIV", true);

        flywayUpTo("343").migrate();

        assertThat(countAccounts()).isEqualTo(1);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT * FROM mobile_money_payout_accounts")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getObject("user_id", UUID.class)).isEqualTo(a);
            assertThat(rs.getString("provider")).isEqualTo("ORANGE_CIV");
            assertThat(rs.getString("country")).isEqualTo("CI");
            assertThat(rs.getString("currency")).isEqualTo("XOF");
            assertThat(rs.getString("msisdn")).isEqualTo(CIPHERTEXT);
            assertThat(rs.getString("msisdn_masked")).isEqualTo("+225 ** ** 12");
            assertThat(rs.getBoolean("migrated")).isTrue();
            assertThat(rs.getString("legacy_accepted_providers")).isEqualTo("ORANGE_CIV,MTN_MOMO_CIV");
            assertThat(rs.getTimestamp("verified_at").toInstant().toString()).isEqualTo("2026-03-04T10:11:12Z");
            assertThat(rs.getTimestamp("deleted_at")).isNull();
            assertThat(rs.next()).isFalse();
        }

        assertThat(currenciesOf(a)).isEqualTo("XOF");
        assertThat(currenciesOf(b)).isNull();
        assertThat(currenciesOf(c)).isNull();
        assertThat(currenciesOf(d)).isNull();

        // ACTIVE sans compte repris : passé DISABLED (plus aucun ACTIVE sans ligne de compte).
        assertThat(statusOf(a)).isEqualTo("ACTIVE");
        assertThat(statusOf(b)).isEqualTo("DISABLED");
        assertThat(statusOf(c)).isEqualTo("DISABLED");
        assertThat(statusOf(d)).as("utilisateur supprimé : intouché").isEqualTo("ACTIVE");
    }

    @Test
    void afterV343_anActiveUserWithOnlyTheCurrencyMissing_isDisabled() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, firebase_uid, username, status, mobile_money_status, mobile_money_msisdn,
                        mobile_money_msisdn_masked, mobile_money_provider, mobile_money_country, mobile_money_currency,
                        created_at, updated_at)
                    VALUES ('%s', 'uid-nc', 'user-nc', 'ACTIVE', 'ACTIVE', 'c', '+225 ** 1', 'ORANGE_CIV', 'CI', NULL, now(), now())
                    """.formatted(id));
        }

        flywayUpTo("343").migrate();

        assertThat(countAccounts()).isZero();
        assertThat(statusOf(id)).isEqualTo("DISABLED");
        assertThat(currenciesOf(id)).isNull();
    }

    @Test
    void afterV343_aMigratedAccountWithoutVerifiedAt_getsTheMigrationInstant() throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO users (id, firebase_uid, username, status, mobile_money_status, mobile_money_msisdn,
                        mobile_money_msisdn_masked, mobile_money_provider, mobile_money_country, mobile_money_currency,
                        created_at, updated_at)
                    VALUES ('%s', 'uid-nv', 'user-nv', 'ACTIVE', 'ACTIVE', 'c', '+225 ** 1', 'MTN_MOMO_CIV', 'CI', 'XOF', now(), now())
                    """.formatted(id));
        }

        flywayUpTo("343").migrate();

        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT verified_at, legacy_accepted_providers FROM mobile_money_payout_accounts")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp("verified_at")).isNotNull();
            assertThat(rs.getString("legacy_accepted_providers")).isNull();
        }
    }

    @Test
    void afterV343_theUniqueIndexAppliesToLiveAccountsOnly() throws Exception {
        UUID a = seedUser("ACTIVE", CIPHERTEXT, "ORANGE_CIV,MTN_MOMO_CIV", false);
        flywayUpTo("343").migrate();

        assertThatThrownBy(() -> insertAccount(a, "ORANGE_CIV", false))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("uq_mm_payout_accounts_user_provider_live");
        insertAccount(a, "ORANGE_CIV", true);
        insertAccount(a, "MTN_MOMO_CIV", false);
        assertThat(countAccounts()).isEqualTo(3);
    }
}
