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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V401 (modals d'étape du colis) : la contrainte de fréquence accepte ONCE_PER_PARCEL, la colonne
 * parcel_roles est ajoutée nulle, et les campagnes existantes traversent la migration intactes.
 */
@DisplayName("V401 : modals d'étape du colis (ONCE_PER_PARCEL, parcel_roles)")
class V401VideoModalParcelStepsMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static final UUID EXISTING = UUID.randomUUID();
    private static final UUID EXISTING_EVERY_TIME = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        jdbc = new JdbcTemplate(dataSource);
        flywayUpTo("400").migrate();
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers, frequency)
                VALUES (?, 'Ancienne', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 'Titre', '["APP_OPEN"]', 'UP_TO_N')
                """, EXISTING);
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers, frequency)
                VALUES (?, 'Livraison', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 'Titre', '["DELIVERY_CONFIRMED"]', 'EVERY_TIME')
                """, EXISTING_EVERY_TIME);
        flywayUpTo("401").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String version) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(version).load();
    }

    private static void insert(String frequency, String parcelRoles) {
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers, frequency, parcel_roles)
                VALUES (?, 'Test', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 'Titre', '["PARCEL_ARRIVED"]', ?, ?)
                """, UUID.randomUUID(), frequency, parcelRoles);
    }

    @Test
    @DisplayName("ONCE_PER_PARCEL s'écrit avec des rôles ; une fréquence inconnue reste refusée")
    void oncePerParcelAcceptee() {
        insert("ONCE_PER_PARCEL", "[\"SENDER\",\"TRAVELER\",\"RECIPIENT\"]");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM video_modal_campaigns WHERE frequency = 'ONCE_PER_PARCEL'", Integer.class))
                .isEqualTo(1);
        assertThatThrownBy(() -> insert("ONCE_PER_TRIP", null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("les cinq fréquences d'avant restent acceptées")
    void anciennesValeurs() {
        for (String f : new String[] {"ONCE", "UP_TO_N", "EVERY_OPEN", "EVERY_TIME"}) insert(f, null);
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers, frequency, frequency_days)
                VALUES (?, 'Jours', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 'Titre', '["APP_OPEN"]', 'EVERY_N_DAYS', 3)
                """, UUID.randomUUID());
        assertThat(jdbc.queryForList("SELECT DISTINCT frequency FROM video_modal_campaigns", String.class))
                .contains("ONCE", "UP_TO_N", "EVERY_OPEN", "EVERY_TIME", "EVERY_N_DAYS");
    }

    @Test
    @DisplayName("parcel_roles : TEXT nullable, sans défaut ; les campagnes d'avant gardent NULL et leur fréquence")
    void campagnesExistantes() {
        Map<String, Object> column = jdbc.queryForMap("""
                SELECT data_type, is_nullable, column_default FROM information_schema.columns
                WHERE table_name = 'video_modal_campaigns' AND column_name = 'parcel_roles'""");
        assertThat(column.get("data_type")).isEqualTo("text");
        assertThat(column.get("is_nullable")).isEqualTo("YES");
        assertThat(column.get("column_default")).isNull();

        assertThat(jdbc.queryForMap("SELECT frequency, parcel_roles FROM video_modal_campaigns WHERE id = ?", EXISTING))
                .containsEntry("frequency", "UP_TO_N").containsEntry("parcel_roles", null);
        assertThat(jdbc.queryForMap("SELECT frequency, parcel_roles FROM video_modal_campaigns WHERE id = ?",
                EXISTING_EVERY_TIME)).containsEntry("frequency", "EVERY_TIME").containsEntry("parcel_roles", null);
    }
}
