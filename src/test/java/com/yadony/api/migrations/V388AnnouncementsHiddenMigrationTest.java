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

/** V388 : colonne announcements.hidden additive, faux pour tous les trajets existants. */
@DisplayName("V388 : trajet caché (announcements.hidden), additive")
class V388AnnouncementsHiddenMigrationTest {

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
        flywayUpTo("387").migrate();
        // Un trajet écrit avant la migration : on ne renseigne que les colonnes obligatoires sans défaut.
        jdbc.execute("ALTER TABLE announcements DISABLE TRIGGER ALL");
        insertBare(EXISTING);
        flywayUpTo("388").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String version) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(version).load();
    }

    /** Insère une ligne minimale : les colonnes NOT NULL sans défaut sont découvertes dans le catalogue. */
    private static void insertBare(UUID id) {
        var cols = jdbc.queryForList("""
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_name = 'announcements' AND table_schema = 'public'
                  AND is_nullable = 'NO' AND column_default IS NULL
                """);
        StringBuilder names = new StringBuilder();
        StringBuilder values = new StringBuilder();
        for (Map<String, Object> col : cols) {
            String name = (String) col.get("column_name");
            String type = (String) col.get("data_type");
            if (names.length() > 0) { names.append(", "); values.append(", "); }
            names.append(name);
            Map<String, String> fixed = Map.of("arrival_city", "'Dakar'", "pickup_lat", "48.88", "pickup_lng", "2.35",
                    "delivery_lat", "14.7", "delivery_lng", "-17.4", "transport_mode", "'PLANE'");
            if (fixed.containsKey(name)) values.append(fixed.get(name));
            else if (type.equals("uuid")) values.append("'").append(UUID.randomUUID()).append("'");
            else if (type.startsWith("character") || type.equals("text")) values.append("'Paris'");
            else if (type.equals("date")) values.append("CURRENT_DATE");
            else if (type.startsWith("timestamp")) values.append("now()");
            else if (type.equals("boolean")) values.append("false");
            else values.append("1");
        }
        // Trajet au kilo : capacité et prix renseignés (contrainte chk_announcements_kg_or_m3).
        jdbc.update("INSERT INTO announcements (id, " + names + ", available_kg, total_kg, price_per_kg) VALUES (?, "
                + values + ", 20, 23, 8)", id);
    }

    @Test
    @DisplayName("la colonne est booléenne, NOT NULL, défaut false")
    void colonne() {
        Map<String, Object> col = jdbc.queryForMap("""
                SELECT data_type, is_nullable, column_default FROM information_schema.columns
                WHERE table_name = 'announcements' AND column_name = 'hidden'
                """);
        assertThat(col.get("data_type")).isEqualTo("boolean");
        assertThat(col.get("is_nullable")).isEqualTo("NO");
        assertThat(String.valueOf(col.get("column_default"))).containsIgnoringCase("false");
    }

    @Test
    @DisplayName("un trajet écrit avant la migration reste visible (hidden = false)")
    void trajetExistantVisible() {
        assertThat(jdbc.queryForObject("SELECT hidden FROM announcements WHERE id = ?", Boolean.class, EXISTING))
                .isFalse();
    }

    @Test
    @DisplayName("un trajet neuf est visible par défaut et peut être caché puis réaffiché")
    void cacherReafficher() {
        jdbc.update("UPDATE announcements SET hidden = true WHERE id = ?", EXISTING);
        assertThat(jdbc.queryForObject("SELECT hidden FROM announcements WHERE id = ?", Boolean.class, EXISTING)).isTrue();
        jdbc.update("UPDATE announcements SET hidden = false WHERE id = ?", EXISTING);
        assertThat(jdbc.queryForObject("SELECT hidden FROM announcements WHERE id = ?", Boolean.class, EXISTING)).isFalse();
    }
}
