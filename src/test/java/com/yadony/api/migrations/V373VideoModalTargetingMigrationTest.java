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
 * V373 : moment, ciblage et fréquence des modals. Des lignes écrites AVANT la migration (V372, comme la
 * vidéo d'accueil en staging) doivent la traverser en gardant exactement leur comportement : « jusqu'à N
 * fois », arrêt après une réponse, aucune condition, aucune situation, toutes versions et plateformes.
 */
@DisplayName("V373 : défauts des campagnes et réponses existantes, contraintes, colonnes d'absence")
class V373VideoModalTargetingMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private static final UUID CAMPAIGN = UUID.randomUUID();
    private static final UUID INFO = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        jdbc = new JdbcTemplate(dataSource);

        flywayUpTo("372").migrate();
        // État d'avant : une campagne vidéo active, un modal Information, un utilisateur et sa réponse.
        jdbc.update("INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at, last_seen_at) "
                + "VALUES (?, ?, ?, 'ACTIVE', now(), now(), now() - interval '40 days')", USER, "uid-" + USER, "u-" + USER.toString().substring(0, 8));
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers, max_displays, active)
                VALUES (?, 'Vidéo d''accueil', 'https://youtu.be/XLgkHom38_Y', 'XLgkHom38_Y', 'Bienvenue', '["APP_OPEN"]', 3, TRUE)
                """, CAMPAIGN);
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, type, title_fr, body_fr, icon, primary_action, triggers, max_displays, active)
                VALUES (?, 'Info', 'INFO', 'Titre', 'Texte', 'INFO', '{"kind":"CLOSE"}', '["SEARCH_TAB"]', 2, TRUE)
                """, INFO);
        jdbc.update("""
                INSERT INTO video_modal_responses (campaign_id, user_id, version, displays, status, last_shown_at)
                VALUES (?, ?, 1, 2, 'DISMISSED', now())
                """, CAMPAIGN, USER);

        flywayUpTo("373").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String version) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(version).load();
    }

    @Test
    @DisplayName("les campagnes existantes prennent les défauts : UP_TO_N, arrêt après réponse, aucun ciblage")
    void campagnesExistantes() {
        for (Map<String, Object> row : jdbc.queryForList("SELECT * FROM video_modal_campaigns WHERE id IN (?, ?)", CAMPAIGN, INFO)) {
            assertThat(row.get("frequency")).isEqualTo("UP_TO_N");
            assertThat(row.get("frequency_days")).isNull();
            assertThat(row.get("stop_after_answer")).isEqualTo(true);
            assertThat(row.get("allow_dont_show_again")).isEqualTo(false);
            assertThat(row.get("priority")).isEqualTo(0);
            assertThat(row.get("connected_only")).isEqualTo(false);
            assertThat(row.get("conditions")).isNull();
            assertThat(row.get("min_app_version")).isNull();
            assertThat(row.get("max_app_version")).isNull();
            assertThat(row.get("platforms")).isEqualTo("[]");
            assertThat(row.get("sample_percent")).isEqualTo(100);
            assertThat(row.get("situations")).isEqualTo("[]");
            // Rien d'autre n'a bougé : mêmes déclencheurs, mêmes plafonds, toujours active.
            assertThat(row.get("active")).isEqualTo(true);
            assertThat(row.get("layout")).isEqualTo("BOTTOM_SHEET");
        }
        assertThat(jdbc.queryForObject("SELECT max_displays FROM video_modal_campaigns WHERE id = ?", Integer.class, CAMPAIGN)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT triggers FROM video_modal_campaigns WHERE id = ?", String.class, CAMPAIGN)).isEqualTo("[\"APP_OPEN\"]");
    }

    @Test
    @DisplayName("les réponses existantes n'ont pas de « ne plus afficher » et gardent leur statut")
    void reponsesExistantes() {
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM video_modal_responses WHERE campaign_id = ?", CAMPAIGN);
        assertThat(row.get("dont_show_again_at")).isNull();
        assertThat(row.get("status")).isEqualTo("DISMISSED");
        assertThat(row.get("displays")).isEqualTo(2);
    }

    @Test
    @DisplayName("users : colonnes d'absence nulles, last_seen_at intacte")
    void utilisateurs() {
        Map<String, Object> row = jdbc.queryForMap("SELECT absence_started_at, returned_at, last_seen_at FROM users WHERE id = ?", USER);
        assertThat(row.get("absence_started_at")).isNull();
        assertThat(row.get("returned_at")).isNull();
        assertThat(row.get("last_seen_at")).isNotNull();
    }

    private void insertCampaign(String extraColumns, String extraValues) {
        jdbc.update("INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr" + extraColumns
                + ") VALUES (?, 'n', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 't'" + extraValues + ")", UUID.randomUUID());
    }

    @Test
    @DisplayName("contraintes : fréquence connue, jours 1..365, EVERY_N_DAYS exige des jours, tirage 1..100")
    void contraintes() {
        insertCampaign(", frequency, frequency_days", ", 'EVERY_N_DAYS', 7");
        insertCampaign(", frequency, sample_percent", ", 'EVERY_OPEN', 1");
        insertCampaign(", frequency, sample_percent", ", 'ONCE', 100");
        for (String bad : new String[] {
                ", frequency) VALUES (?, 'n', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 't', 'SOMETIMES'",
                ", frequency) VALUES (?, 'n', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 't', 'EVERY_N_DAYS'",
                ", frequency_days) VALUES (?, 'n', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 't', 0",
                ", frequency_days) VALUES (?, 'n', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 't', 366",
                ", sample_percent) VALUES (?, 'n', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 't', 0",
                ", sample_percent) VALUES (?, 'n', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 't', 101"}) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr"
                    + bad + ")", UUID.randomUUID())).as(bad).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
