package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V376 (DAC7, lot 3a) : données fiscales du voyageur sur {@code users} et registre des diligences immuable.
 * Un compte existant traverse la migration sans donnée fiscale et avec un compteur de rappels à zéro.
 */
@DisplayName("V376 : colonnes fiscales des voyageurs et registre DAC7 immuable")
class V376Dac7TaxInfoMigrationTest {

    private static EmbeddedPostgres postgres;
    private static JdbcTemplate jdbc;
    private static final UUID USER = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        DataSource dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        jdbc = new JdbcTemplate(dataSource);
        flyway(dataSource, "375").migrate();
        jdbc.update("INSERT INTO users (id, firebase_uid, username, country) VALUES (?, ?, ?, 'FR')",
                USER, "uid-v376-" + USER, "v376_" + USER.toString().substring(0, 8));
        flyway(dataSource, "376").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).load();
    }

    @Test
    @DisplayName("un compte existant n'a aucune donnée fiscale et zéro rappel")
    void compteExistant() {
        Map<String, Object> row = jdbc.queryForMap("SELECT tax_identification_number, tax_country, vat_number, "
                + "tax_info_requested_at, tax_reminder_count, tax_info_provided_at FROM users WHERE id = ?", USER);
        assertThat(row.get("tax_identification_number")).isNull();
        assertThat(row.get("tax_country")).isNull();
        assertThat(row.get("vat_number")).isNull();
        assertThat(row.get("tax_info_requested_at")).isNull();
        assertThat(row.get("tax_reminder_count")).isEqualTo(0);
        assertThat(row.get("tax_info_provided_at")).isNull();
    }

    @Test
    @DisplayName("les colonnes chiffrées sont assez larges pour un texte chiffré (comme pro_siret)")
    void colonnesChiffreesLarges() {
        for (String column : new String[] {"tax_identification_number", "tax_country", "vat_number"}) {
            Integer length = jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns "
                    + "WHERE table_name = 'users' AND column_name = ?", Integer.class, column);
            assertThat(length).as(column).isEqualTo(255);
        }
    }

    @Test
    @DisplayName("résidence DAC7 figée : nulle par défaut, statut contrôlé")
    void residenceFigee() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tax_residence_country, tax_seller_status FROM users WHERE id = ?", USER);
        assertThat(row.get("tax_residence_country")).isNull();
        assertThat(row.get("tax_seller_status")).isNull();
        jdbc.update("UPDATE users SET tax_residence_country = 'BE', tax_seller_status = 'PROFESSIONAL' WHERE id = ?",
                USER);
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET tax_seller_status = 'COMPANY' WHERE id = ?", USER))
                .hasMessageContaining("chk_users_tax_seller_status");
        jdbc.update("UPDATE users SET tax_residence_country = NULL, tax_seller_status = NULL WHERE id = ?", USER);
    }

    @Test
    @DisplayName("suppléments et modifications indexés par voyageur")
    void indexParVoyageur() {
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE indexname IN "
                + "('idx_bid_supplements_traveler', 'idx_bid_amendments_traveler')", String.class)).hasSize(2);
    }

    @Test
    @DisplayName("compteur de rappels négatif refusé")
    void compteurNegatifRefuse() {
        assertThatThrownBy(() -> jdbc.update("UPDATE users SET tax_reminder_count = -1 WHERE id = ?", USER))
                .hasMessageContaining("chk_users_tax_reminder_count");
    }

    @Test
    @DisplayName("registre : une ligne s'ajoute, ne se modifie pas et ne se supprime pas")
    void registreImmuable() {
        Long id = jdbc.queryForObject("INSERT INTO dac7_due_diligence_log (user_id, action, channel, note) "
                + "VALUES (?, 'REQUEST', 'PUSH', 'Premier versement') RETURNING id", Long.class, USER);
        assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL FROM dac7_due_diligence_log WHERE id = ?",
                Boolean.class, id)).isTrue();

        assertThatThrownBy(() -> jdbc.update("UPDATE dac7_due_diligence_log SET note = 'x' WHERE id = ?", id))
                .hasMessageContaining("immuable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM dac7_due_diligence_log WHERE id = ?", id))
                .hasMessageContaining("immuable");
        assertThat(jdbc.queryForObject("SELECT note FROM dac7_due_diligence_log WHERE id = ?", String.class, id))
                .isEqualTo("Premier versement");
    }

    @Test
    @DisplayName("registre : action et canal hors liste refusés")
    void registreValeursControlees() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dac7_due_diligence_log (user_id, action, channel) "
                + "VALUES (?, 'REMINDER_3', 'PUSH')", USER))
                .hasMessageContaining("chk_dac7_due_diligence_log_action");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dac7_due_diligence_log (user_id, action, channel) "
                + "VALUES (?, 'REQUEST', 'FAX')", USER))
                .hasMessageContaining("chk_dac7_due_diligence_log_channel");
    }
}
