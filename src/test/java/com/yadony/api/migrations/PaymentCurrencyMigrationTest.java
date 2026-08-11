package com.yadony.api.migrations;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentCurrencyMigrationTest {

    @Test
    void migration_adds_non_null_currency_with_eur_history_default() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V194__add_payment_currency.sql");

        assertThat(resource).as("V194 payment currency migration").isNotNull();
        String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).contains("add column currency");
        assertThat(sql).contains("default 'eur'");
        assertThat(sql).contains("not null");
    }

    @Test
    void migration_widens_business_preferences_currency_constraint_to_exact_supported_set() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V196__widen_business_prefs_currency.sql");

        assertThat(resource).as("V196 business prefs currency widening migration").isNotNull();
        String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).contains("drop constraint chk_currency");
        assertThat(sql).contains("add constraint chk_currency check");
        assertThat(sql)
                .contains("currency_code in ('eur', 'usd', 'cad', 'gbp', 'chf', 'xof', 'xaf')");
        assertThat(sql).contains("'eur'");
        assertThat(sql).contains("'usd'");
        assertThat(sql).contains("'cad'");
        assertThat(sql).contains("'gbp'");
        assertThat(sql).contains("'chf'");
        assertThat(sql).contains("'xof'");
        assertThat(sql).contains("'xaf'");
    }

    @Test
    void migration_adds_bid_currency_column_with_eur_default_and_check_constraint() throws Exception {
        var resource = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V198__bid_negotiation_wallet_tx_currency.sql");

        assertThat(resource).as("V198 bid/negotiation/wallet-tx currency migration").isNotNull();
        String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        assertThat(sql).contains("alter table bids");
        assertThat(sql).contains("add column currency varchar(3) not null default 'eur'");
        assertThat(sql).contains("add constraint chk_bids_currency");
        assertThat(sql)
                .contains("currency in ('eur', 'usd', 'cad', 'gbp', 'chf', 'xof', 'xaf')");
    }
}
