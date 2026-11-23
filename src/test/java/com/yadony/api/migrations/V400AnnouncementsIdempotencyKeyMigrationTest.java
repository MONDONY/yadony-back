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
 * V400 : clé {@code Idempotency-Key} des créations de trajet, additive. Colonne nullable (trajets existants et
 * anciennes versions de l'app sans clé), index unique partiel par voyageur hors soft-delete.
 */
@DisplayName("V400 : announcements.idempotency_key et son index unique partiel")
class V400AnnouncementsIdempotencyKeyMigrationTest {

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
        flywayUpTo("399").migrate();
        // Un trajet écrit avant la migration : on ne renseigne que les colonnes obligatoires sans défaut.
        jdbc.execute("ALTER TABLE announcements DISABLE TRIGGER ALL");
        insertBare(EXISTING, UUID.randomUUID(), null);
        flywayUpTo("400").migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String version) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(version).load();
    }

    /**
     * Insère une ligne minimale : les colonnes NOT NULL sans défaut sont découvertes dans le catalogue, le
     * voyageur et la clé sont imposés.
     */
    private static void insertBare(UUID id, UUID travelerId, String key) {
        var cols = jdbc.queryForList("""
                SELECT column_name, data_type FROM information_schema.columns
                WHERE table_name = 'announcements' AND table_schema = 'public'
                  AND is_nullable = 'NO' AND column_default IS NULL AND column_name <> 'traveler_id'
                """);
        StringBuilder names = new StringBuilder("id, traveler_id");
        StringBuilder values = new StringBuilder("?, ?");
        for (Map<String, Object> col : cols) {
            String name = (String) col.get("column_name");
            if (name.equals("id")) continue;
            String type = (String) col.get("data_type");
            names.append(", ").append(name);
            values.append(", ");
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
        if (key == null) {
            jdbc.update("INSERT INTO announcements (" + names + ", available_kg, total_kg, price_per_kg) VALUES ("
                    + values + ", 20, 23, 8)", id, travelerId);
        } else {
            jdbc.update("INSERT INTO announcements (" + names + ", available_kg, total_kg, price_per_kg, idempotency_key)"
                    + " VALUES (" + values + ", 20, 23, 8, ?)", id, travelerId, key);
        }
    }

    @Test
    @DisplayName("la colonne est un varchar(64) nullable, vide pour les trajets d'avant la migration")
    void colonne() {
        Map<String, Object> col = jdbc.queryForMap("""
                SELECT data_type, character_maximum_length, is_nullable, column_default FROM information_schema.columns
                WHERE table_name = 'announcements' AND column_name = 'idempotency_key'
                """);
        assertThat(col.get("data_type")).isEqualTo("character varying");
        assertThat(((Number) col.get("character_maximum_length")).intValue()).isEqualTo(64);
        assertThat(col.get("is_nullable")).isEqualTo("YES");
        assertThat(col.get("column_default")).isNull();
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM announcements WHERE id = ?", String.class, EXISTING))
                .isNull();
    }

    @Test
    @DisplayName("l'index est unique et partiel : (traveler_id, idempotency_key), clé renseignée, hors soft-delete")
    void index() {
        String def = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_announcements_traveler_idempotency_key'",
                String.class);
        assertThat(def).contains("UNIQUE").contains("(traveler_id, idempotency_key)")
                .contains("idempotency_key IS NOT NULL").contains("deleted_at IS NULL");
    }

    @Test
    @DisplayName("même voyageur, même clé : la seconde insertion est refusée")
    void memeVoyageurMemeCle_refusee() {
        UUID traveler = UUID.randomUUID();
        insertBare(UUID.randomUUID(), traveler, "cle-unique-0001");

        assertThatThrownBy(() -> insertBare(UUID.randomUUID(), traveler, "cle-unique-0001"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("même clé pour deux voyageurs, et plusieurs trajets sans clé : acceptés")
    void cleParVoyageur_etSansCle_acceptes() {
        UUID traveler = UUID.randomUUID();
        insertBare(UUID.randomUUID(), traveler, "cle-partagee-01");
        insertBare(UUID.randomUUID(), UUID.randomUUID(), "cle-partagee-01");
        insertBare(UUID.randomUUID(), traveler, null);
        insertBare(UUID.randomUUID(), traveler, null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM announcements WHERE traveler_id = ?", Integer.class,
                traveler)).isEqualTo(3);
    }

    @Test
    @DisplayName("un trajet supprimé (soft delete) libère sa clé")
    void trajetSupprime_libereSaCle() {
        UUID traveler = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        insertBare(deleted, traveler, "cle-liberee-001");
        jdbc.update("UPDATE announcements SET deleted_at = now() WHERE id = ?", deleted);

        insertBare(UUID.randomUUID(), traveler, "cle-liberee-001");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM announcements WHERE traveler_id = ? AND idempotency_key = 'cle-liberee-001'",
                Integer.class, traveler)).isEqualTo(2);
    }
}
