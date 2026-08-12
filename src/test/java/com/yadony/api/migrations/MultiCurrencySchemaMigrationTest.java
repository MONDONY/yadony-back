package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiCurrencySchemaMigrationTest {

    private static final List<String> SUPPORTED_CURRENCIES =
            List.of("EUR", "USD", "CAD", "GBP", "CHF", "XOF", "XAF");

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDb() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDb() throws Exception {
        if (postgres != null) {
            postgres.close();
        }
    }

    @BeforeEach
    void resetSchema() {
        Flyway flyway = flywayUpTo("201");
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void sqlResources_exist_and_define_the_exact_currency_contract() throws Exception {
        assertMigrationContains(
                "db/migration/V199__announcement_package_request_currency.sql",
                "add column currency",
                "default 'eur'",
                "currency in ('eur', 'usd', 'cad', 'gbp', 'chf', 'xof', 'xaf')");
        assertMigrationContains(
                "db/migration/V200__bid_negotiation_wallet_tx_currency.sql",
                "add column currency",
                "default 'eur'",
                "currency in ('eur', 'usd', 'cad', 'gbp', 'chf', 'xof', 'xaf')");
        assertMigrationContainsWithoutCurrencyList(
                "db/migration/V201__wallet_accounts_per_currency.sql",
                "drop constraint wallet_accounts_user_id_unique",
                "unique (user_id, currency)");
    }

    @Test
    void afterV201_target_tables_have_non_null_eur_currency_columns() throws Exception {
        assertCurrencyColumn("announcements");
        assertCurrencyColumn("package_requests");
        assertCurrencyColumn("bids");
        assertCurrencyColumn("negotiation_threads");
        assertCurrencyColumn("wallet_transactions");
    }

    @Test
    void afterV201_wallet_accounts_allows_one_account_per_currency() throws Exception {
        UUID userId = insertUser();

        insertWalletAccount(userId, "EUR");
        insertWalletAccount(userId, "XOF");

        assertThatThrownBy(() -> insertWalletAccount(userId, "EUR"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("wallet");

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*) FROM wallet_accounts WHERE user_id = '" + userId + "'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(2);
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

    private void assertMigrationContains(String resourcePath, String... snippets) throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(resourcePath);
        assertThat(resource).as(resourcePath).isNotNull();

        String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        for (String snippet : snippets) {
            assertThat(sql).contains(snippet);
        }
        for (String currency : SUPPORTED_CURRENCIES) {
            assertThat(sql).contains("'" + currency.toLowerCase() + "'");
        }
    }

    private void assertMigrationContainsWithoutCurrencyList(String resourcePath, String... snippets) throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(resourcePath);
        assertThat(resource).as(resourcePath).isNotNull();

        String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        for (String snippet : snippets) {
            assertThat(sql).contains(snippet);
        }
    }

    private void assertCurrencyColumn(String tableName) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("""
                    SELECT is_nullable, column_default, data_type, character_maximum_length
                    FROM information_schema.columns
                    WHERE table_schema = 'public'
                      AND table_name = '%s'
                      AND column_name = 'currency'
                    """.formatted(tableName));

            assertThat(rs.next()).as("currency column on " + tableName).isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("character varying");
            assertThat(rs.getInt("character_maximum_length")).isEqualTo(3);
            assertThat(rs.getString("is_nullable")).isEqualTo("NO");
            assertThat(rs.getString("column_default")).containsIgnoringCase("eur");
        }
    }

    private UUID insertUser() throws SQLException {
        UUID userId = UUID.randomUUID();
        String suffix = userId.toString().substring(0, 8);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO users
                      (id, firebase_uid, username, status, created_at, updated_at)
                    VALUES
                      ('%s', '%s', '%s', 'ACTIVE', now(), now())
                    """.formatted(userId, "uid-" + suffix, "user-" + suffix));
        }
        return userId;
    }

    private void insertWalletAccount(UUID userId, String currency) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO wallet_accounts
                      (id, user_id, balance, currency, created_at, updated_at)
                    VALUES
                      ('%s', '%s', 0, '%s', now(), now())
                    """.formatted(UUID.randomUUID(), userId, currency));
        }
    }
}
