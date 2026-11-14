package com.yadony.api.migrations;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V386 : la contrainte de fréquence accepte EVERY_TIME, les campagnes existantes la traversent intactes. */
@DisplayName("V386 : fréquence EVERY_TIME acceptée, anciennes valeurs conservées")
class V386VideoModalEveryTimeMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static final UUID EXISTING = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        jdbc = new JdbcTemplate(dataSource);
        flywayUpTo("385").migrate();
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers, frequency)
                VALUES (?, 'Ancienne', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 'Titre', '["APP_OPEN"]', 'EVERY_OPEN')
                """, EXISTING);
        flywayUpTo("386").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String version) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(version).load();
    }

    private static void insert(String frequency) {
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers, frequency)
                VALUES (?, 'Test', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 'Titre', '["DELIVERY_CONFIRMED"]', ?)
                """, UUID.randomUUID(), frequency);
    }

    @Test
    @DisplayName("EVERY_TIME s'écrit, sans frequency_days ni plafond")
    void everyTimeAcceptee() {
        insert("EVERY_TIME");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM video_modal_campaigns WHERE frequency = 'EVERY_TIME'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("les quatre valeurs d'avant restent acceptées et une valeur inconnue reste refusée")
    void anciennesValeurs() {
        for (String f : new String[] {"ONCE", "UP_TO_N", "EVERY_OPEN"}) insert(f);
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers, frequency, frequency_days)
                VALUES (?, 'Jours', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 'Titre', '["APP_OPEN"]', 'EVERY_N_DAYS', 3)
                """, UUID.randomUUID());
        assertThatThrownBy(() -> insert("EVERY_DAY")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("une campagne écrite avant la migration est inchangée")
    void campagneExistante() {
        assertThat(jdbc.queryForObject("SELECT frequency FROM video_modal_campaigns WHERE id = ?", String.class, EXISTING))
                .isEqualTo("EVERY_OPEN");
    }
}
