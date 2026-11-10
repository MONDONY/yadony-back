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

/**
 * V375 : moments d'action des modals. Des campagnes écrites AVANT la migration (V374 : vidéo d'accueil,
 * information, et une campagne à situations) doivent la traverser en gardant exactement leur comportement :
 * mêmes déclencheurs, seuils par défaut (2 résultats, 3 consultations) et aucun corridor.
 */
@DisplayName("V375 : défauts des campagnes existantes (seuils et corridors), déclencheurs inchangés")
class V375VideoModalMomentsMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private static final UUID VIDEO = UUID.randomUUID();
    private static final UUID INFO = UUID.randomUUID();
    private static final UUID SITUATED = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        jdbc = new JdbcTemplate(dataSource);

        flywayUpTo("374").migrate();
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers, max_displays, active)
                VALUES (?, 'Vidéo d''accueil', 'https://youtu.be/XLgkHom38_Y', 'XLgkHom38_Y', 'Bienvenue', '["APP_OPEN"]', 3, TRUE)
                """, VIDEO);
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, type, title_fr, body_fr, icon, primary_action, triggers, max_displays, active)
                VALUES (?, 'Info', 'INFO', 'Titre', 'Texte', 'INFO', '{"kind":"CLOSE"}', '["SEARCH_TAB","PUBLISH_TRIP"]', 2, TRUE)
                """, INFO);
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers, situations, frequency, active)
                VALUES (?, 'Situation', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 'Titre', '[]',
                        '[{"kind":"PROFILE_INCOMPLETE"}]', 'EVERY_OPEN', TRUE)
                """, SITUATED);

        flywayUpTo("375").migrate();
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
    @DisplayName("les campagnes existantes prennent les seuils par défaut et aucun corridor")
    void campagnesExistantes() {
        for (UUID id : new UUID[] {VIDEO, INFO, SITUATED}) {
            Map<String, Object> row = jdbc.queryForMap("SELECT * FROM video_modal_campaigns WHERE id = ?", id);
            assertThat(row.get("trigger_options")).as(id.toString()).isEqualTo("{\"fewResultsMax\":2,\"tripViewedTimes\":3}");
            assertThat(row.get("corridors")).isEqualTo("[]");
            assertThat(row.get("active")).isEqualTo(true);
            // Étape 1 intacte.
            assertThat(row.get("stop_after_answer")).isEqualTo(true);
            assertThat(row.get("platforms")).isEqualTo("[]");
        }
    }

    @Test
    @DisplayName("rien d'autre n'a bougé : déclencheurs, plafonds, fréquence et situations sont ceux d'avant")
    void resteInchange() {
        assertThat(jdbc.queryForObject("SELECT triggers FROM video_modal_campaigns WHERE id = ?", String.class, VIDEO))
                .isEqualTo("[\"APP_OPEN\"]");
        assertThat(jdbc.queryForObject("SELECT triggers FROM video_modal_campaigns WHERE id = ?", String.class, INFO))
                .isEqualTo("[\"SEARCH_TAB\",\"PUBLISH_TRIP\"]");
        assertThat(jdbc.queryForObject("SELECT max_displays FROM video_modal_campaigns WHERE id = ?", Integer.class, VIDEO))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT frequency FROM video_modal_campaigns WHERE id = ?", String.class, SITUATED))
                .isEqualTo("EVERY_OPEN");
        assertThat(jdbc.queryForObject("SELECT situations FROM video_modal_campaigns WHERE id = ?", String.class, SITUATED))
                .isEqualTo("[{\"kind\":\"PROFILE_INCOMPLETE\"}]");
    }

    @Test
    @DisplayName("une nouvelle campagne sans les deux colonnes les reçoit aussi par défaut ; les nouveaux déclencheurs s'écrivent sans contrainte")
    void nouvelleCampagne() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO video_modal_campaigns (id, name, youtube_url, youtube_video_id, title_fr, triggers)
                VALUES (?, 'Moment', 'https://youtu.be/dQw4w9WgXcQ', 'dQw4w9WgXcQ', 'Titre', '["DELIVERY_CONFIRMED","APP_UPDATED"]')
                """, id);
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM video_modal_campaigns WHERE id = ?", id);
        assertThat(row.get("trigger_options")).isEqualTo("{\"fewResultsMax\":2,\"tripViewedTimes\":3}");
        assertThat(row.get("corridors")).isEqualTo("[]");
        assertThat(row.get("triggers")).isEqualTo("[\"DELIVERY_CONFIRMED\",\"APP_UPDATED\"]");
    }
}
