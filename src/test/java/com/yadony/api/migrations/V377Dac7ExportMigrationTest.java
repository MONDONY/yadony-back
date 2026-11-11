package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V377 (DAC7, lot 3b) : cache des taux de référence BCE par jour et registre des récapitulatifs annuels envoyés aux
 * voyageurs (un seul par voyageur et par année).
 */
@DisplayName("V377 : taux BCE par jour et récapitulatifs DAC7")
class V377Dac7ExportMigrationTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("377").load().migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    @DisplayName("un taux par devise et par jour, positif, code ISO en majuscules")
    void tauxBce() {
        jdbc.update("INSERT INTO ecb_exchange_rates (currency, rate_date, units_per_eur) VALUES ('USD', DATE "
                + "'2026-03-02', 1.0812)");
        assertThat(jdbc.queryForObject("SELECT units_per_eur FROM ecb_exchange_rates WHERE currency = 'USD'",
                BigDecimal.class)).isEqualByComparingTo("1.0812");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ecb_exchange_rates (currency, rate_date, units_per_eur) "
                + "VALUES ('USD', DATE '2026-03-02', 1.09)")).hasMessageContaining("uq_ecb_exchange_rates");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ecb_exchange_rates (currency, rate_date, units_per_eur) "
                + "VALUES ('GBP', DATE '2026-03-02', 0)")).hasMessageContaining("chk_ecb_exchange_rates_rate");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO ecb_exchange_rates (currency, rate_date, units_per_eur) "
                + "VALUES ('usd', DATE '2026-03-03', 1.08)")).hasMessageContaining("chk_ecb_exchange_rates_currency");
    }

    @Test
    @DisplayName("un seul récapitulatif par voyageur et par année")
    void recapitulatifUnique() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO dac7_seller_statements (user_id, reporting_year) VALUES (?, 2026)", user);
        jdbc.update("INSERT INTO dac7_seller_statements (user_id, reporting_year) VALUES (?, 2027)", user);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dac7_seller_statements (user_id, reporting_year) "
                + "VALUES (?, 2026)", user)).hasMessageContaining("uq_dac7_seller_statements_user_year");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dac7_seller_statements (user_id, reporting_year) "
                + "VALUES (?, 1999)", UUID.randomUUID())).hasMessageContaining("chk_dac7_seller_statements_year");
        assertThat(jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM dac7_seller_statements WHERE user_id = ? "
                + "AND reporting_year = 2026", Boolean.class, user)).isTrue();
    }
}
