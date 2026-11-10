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
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V370 : {@code announcements.published_at}, date de première publication. Rattrapage : les trajets existants
 * qui ne sont pas des brouillons prennent leur date de création ; les brouillons restent à NULL.
 */
class V370AnnouncementsPublishedAtMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static Flyway flywayUpTo(String target) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target(target).cleanDisabled(false).load();
    }

    @Test
    void v370_rattrapeLesNonBrouillonsAvecLaDateDeCreation_etLaisseLesBrouillonsANull() throws Exception {
        Flyway baseline = flywayUpTo("369");
        baseline.clean();
        baseline.migrate();
        UUID traveler = seedUser();
        UUID active = seedAnnouncement(traveler, "ACTIVE", "2026-09-10 08:00:00+00");
        UUID full = seedAnnouncement(traveler, "FULL", "2026-10-02 09:30:00+00");
        UUID completed = seedAnnouncement(traveler, "COMPLETED", "2026-08-20 11:00:00+00");
        UUID draft = seedAnnouncement(traveler, "DRAFT", "2026-10-15 12:00:00+00");

        flywayUpTo("370").migrate();

        assertThat(publishedAt(active)).isEqualTo(createdAt(active));
        assertThat(publishedAt(full)).isEqualTo(createdAt(full));
        assertThat(publishedAt(completed)).isEqualTo(createdAt(completed));
        assertThat(publishedAt(active)).isNotNull();
        assertThat(publishedAt(draft)).isNull();

        // La colonne est nullable (un brouillon neuf n'a pas de date) et un index sert le comptage.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT is_nullable FROM information_schema.columns "
                     + "WHERE table_name = 'announcements' AND column_name = 'published_at'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("YES");
        }
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT indexdef FROM pg_indexes WHERE tablename = 'announcements' "
                     + "AND indexname = 'idx_announcements_traveler_published_at'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).contains("traveler_id").contains("published_at");
        }
    }

    private static UUID seedUser() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("INSERT INTO users (firebase_uid, username) VALUES ('v370-" + suffix
                     + "', 'v370_" + suffix + "') RETURNING id")) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }

    private static UUID seedAnnouncement(UUID travelerId, String status, String createdAt) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("""
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  available_kg, price_per_kg, transport_mode, total_kg,
                  pickup_address_label, pickup_lat, pickup_lng,
                  delivery_address_label, delivery_lat, delivery_lng,
                  status, created_at, updated_at)
                VALUES ('%s', '%s', 'Paris', 'Dakar', CURRENT_DATE + 10,
                  3.00, 15.00, 'PLANE', 3.00,
                  '12 rue de Paris', 48.8566, 2.3522,
                  'Plateau, Dakar', 14.6928, -17.4467,
                  '%s', '%s', now())
                """.formatted(id, travelerId, status, createdAt));
        }
        return id;
    }

    private static Timestamp createdAt(UUID id) throws Exception {
        return timestamp("created_at", id);
    }

    private static Timestamp publishedAt(UUID id) throws Exception {
        return timestamp("published_at", id);
    }

    private static Timestamp timestamp(String column, UUID id) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT " + column + " FROM announcements WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getTimestamp(1);
        }
    }
}
