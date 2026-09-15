package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V257 : le modèle de trajet mémorise tout le formulaire. Les colonnes sont
 * nullables ou à défaut, et accepted_payment_methods est rempli depuis
 * cash_accepted pour les lignes existantes : un modèle créé avant la migration
 * doit ressortir avec STRIPE,CASH s'il acceptait l'espèce, STRIPE sinon.
 */
class V257MigrationTest {

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
        if (postgres != null) postgres.close();
    }

    private Flyway flywayUpTo(String targetVersion) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .schemas("public", "kyc_schema")
                .cleanDisabled(false)
                .target(targetVersion)
                .load();
    }

    private UUID seedUser(Statement s) throws Exception {
        UUID userId = UUID.randomUUID();
        s.execute("INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at) "
                + "VALUES ('" + userId + "', 'uid-" + userId + "', 'user-" + userId.toString().substring(0, 8) + "', "
                + "'ACTIVE', now(), now())");
        return userId;
    }

    @Test
    void backfillsAcceptedPaymentMethodsFromCashAccepted() throws Exception {
        Flyway reset = flywayUpTo("256");
        reset.clean();
        reset.migrate();

        UUID withCash;
        UUID withoutCash;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            UUID userId = seedUser(s);
            withCash = UUID.randomUUID();
            withoutCash = UUID.randomUUID();
            for (UUID id : new UUID[]{withCash, withoutCash}) {
                s.execute("INSERT INTO trip_templates (id, user_id, label, departure_city, arrival_city, "
                        + "transport_mode, capacity_unit, available_kg, price_per_kg, cash_accepted, created_at, updated_at) "
                        + "VALUES ('" + id + "', '" + userId + "', 'Paris-Dakar', 'Paris', 'Dakar', "
                        + "'PLANE', 'SUITCASE_23KG', 23, 8.0, " + (id == withCash) + ", now(), now())");
            }
        }

        flywayUpTo("257").migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            assertThat(paymentMethods(s, withCash)).isEqualTo("STRIPE,CASH");
            assertThat(paymentMethods(s, withoutCash)).isEqualTo("STRIPE");
            try (ResultSet rs = s.executeQuery(
                    "SELECT pricing_mode, negotiable, currency, handover_lead_days FROM trip_templates WHERE id = '" + withCash + "'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("pricing_mode")).isEqualTo("KG");
                assertThat(rs.getBoolean("negotiable")).isFalse();
                assertThat(rs.getString("currency")).isNull();
                assertThat(rs.getObject("handover_lead_days")).isNull();
            }
        }
    }

    @Test
    void pricePerKgBecomesNullable() throws Exception {
        Flyway reset = flywayUpTo("257");
        reset.clean();
        reset.migrate();

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            UUID userId = seedUser(s);
            s.execute("INSERT INTO trip_templates (id, user_id, label, departure_city, arrival_city, "
                    + "transport_mode, capacity_unit, available_kg, price_per_kg, pricing_mode, created_at, updated_at) "
                    + "VALUES ('" + UUID.randomUUID() + "', '" + userId + "', 'Grille', 'Paris', 'Dakar', "
                    + "'PLANE', 'SUITCASE_23KG', 23, NULL, 'MIXED', now(), now())");
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM trip_templates WHERE price_per_kg IS NULL")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }
    }

    private static String paymentMethods(Statement s, UUID id) throws Exception {
        try (ResultSet rs = s.executeQuery(
                "SELECT accepted_payment_methods FROM trip_templates WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
