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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V378 (DAC7, collecte ciblée) : lieu de naissance chiffré (seulement sans NIF) et, au registre des diligences, la
 * source d'une information (saisie ou pièce vérifiée) avec l'identifiant de vérification comme preuve. Chaîne Flyway
 * complète sur PostgreSQL : le registre reste immuable après l'ajout des colonnes.
 */
@DisplayName("V378 : lieu de naissance et source des informations DAC7")
class V378Dac7CollecteCibleeMigrationTest {

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
                .schemas("public", "kyc_schema").target("378").load().migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    @Test
    @DisplayName("users.tax_birth_place accepte un texte chiffré (base64 long) et reste nul par défaut")
    void lieuDeNaissance() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, firebase_uid, username) VALUES (?, ?, ?)", id, "uid-" + id,
                "v378_" + id.toString().substring(0, 8));
        assertThat(jdbc.queryForObject("SELECT tax_birth_place FROM users WHERE id = ?", String.class, id)).isNull();
        String chiffre = "A".repeat(500);
        jdbc.update("UPDATE users SET tax_birth_place = ? WHERE id = ?", chiffre, id);
        assertThat(jdbc.queryForObject("SELECT tax_birth_place FROM users WHERE id = ?", String.class, id))
                .isEqualTo(chiffre);
    }

    @Test
    @DisplayName("registre : source USER ou DIDIT avec la référence de vérification, toujours immuable")
    void sourceAuRegistre() {
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO dac7_due_diligence_log (user_id, action, channel, source, verification_ref, note) "
                + "VALUES (?, 'PROVIDED', 'IN_APP', 'DIDIT', 'session-123', 'NAME,BIRTH_DATE')", user);
        jdbc.update("INSERT INTO dac7_due_diligence_log (user_id, action, channel, source) "
                + "VALUES (?, 'PROVIDED', 'IN_APP', 'USER')", user);
        // Lignes antérieures : sans source.
        jdbc.update("INSERT INTO dac7_due_diligence_log (user_id, action, channel) VALUES (?, 'REQUEST', 'IN_APP')",
                user);
        assertThat(jdbc.queryForObject("SELECT verification_ref FROM dac7_due_diligence_log WHERE user_id = ? "
                + "AND source = 'DIDIT'", String.class, user)).isEqualTo("session-123");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO dac7_due_diligence_log (user_id, action, channel, source) "
                + "VALUES (?, 'PROVIDED', 'IN_APP', 'STRIPE')", user))
                .hasMessageContaining("chk_dac7_due_diligence_log_source");
        assertThatThrownBy(() -> jdbc.update("UPDATE dac7_due_diligence_log SET verification_ref = 'autre' "
                + "WHERE user_id = ?", user)).hasMessageContaining("immuable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM dac7_due_diligence_log WHERE user_id = ?", user))
                .hasMessageContaining("immuable");
    }
}
