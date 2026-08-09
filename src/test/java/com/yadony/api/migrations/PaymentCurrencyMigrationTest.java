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
}
