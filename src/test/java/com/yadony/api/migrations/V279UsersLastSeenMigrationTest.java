package com.yadony.api.migrations;

import com.yadony.api.auth.UserRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V279 : dernière connexion et son réglage de visibilité ; et, sur le même
 * PostgreSQL réel, la requête du temps de réponse mesuré (percentile_cont n'existe
 * pas sous H2).
 */
class V279UsersLastSeenMigrationTest {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
        dataSource = postgres.getPostgresDatabase();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS kyc_schema");
        }
        Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .schemas("public", "kyc_schema").target("279").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (postgres != null) postgres.close();
    }

    private static void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static UUID user() throws Exception {
        UUID id = UUID.randomUUID();
        exec("INSERT INTO users (id, firebase_uid, username, status, created_at, updated_at) VALUES ('%s', '%s', '%s', 'ACTIVE', now(), now())"
                .formatted(id, "uid-" + id, "u-" + id.toString().substring(0, 8)));
        return id;
    }

    private static UUID trip(UUID traveler) throws Exception {
        UUID id = UUID.randomUUID();
        exec("""
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  available_kg, price_per_kg, transport_mode, total_kg,
                  pickup_address_label, pickup_lat, pickup_lng,
                  delivery_address_label, delivery_lat, delivery_lng, created_at, updated_at)
                VALUES ('%s', '%s', 'Paris', 'Dakar', CURRENT_DATE + 10, 3.00, 15.00, 'PLANE', 3.00,
                  '12 rue de Paris', 48.8566, 2.3522, 'Plateau, Dakar', 14.6928, -17.4467, now(), now())
                """.formatted(id, traveler));
        return id;
    }

    /** Demande créée il y a {@code ageMinutes}, décidée par {@code actor} il y a {@code decidedMinutesAgo}. */
    private static void decidedBid(UUID trip, UUID sender, UUID actor, String action,
                                   int ageMinutes, int decidedMinutesAgo) throws Exception {
        UUID bid = UUID.randomUUID();
        exec("""
                INSERT INTO bids (id, announcement_id, sender_id, weight_kg, description, recipient_name,
                  recipient_phone, status, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 3.00, 'Vetements', 'Aminata', '+221701234567', 'ACCEPTED',
                  now() - interval '%d minutes', now())
                """.formatted(bid, trip, sender, ageMinutes));
        exec("INSERT INTO audit_log (entity_type, entity_id, action, actor_id, created_at) VALUES ('BID', '%s', '%s', '%s', now() - interval '%d minutes')"
                .formatted(bid, action, actor, decidedMinutesAgo));
    }

    private static Map<String, Object> stats(UUID traveler) {
        return new NamedParameterJdbcTemplate(dataSource).queryForMap(UserRepository.TRAVELER_DECISION_DELAY_SQL,
                new MapSqlParameterSource()
                        .addValue("travelerId", traveler)
                        .addValue("since", OffsetDateTime.now(ZoneOffset.UTC).minusDays(90)));
    }

    @Test
    void v279_ajouteLaDerniereConnexionVisibleParDefaut() throws Exception {
        UUID id = user();
        Map<String, Object> row = new NamedParameterJdbcTemplate(dataSource).queryForMap(
                "SELECT last_seen_at, show_last_seen FROM users WHERE id = :id",
                new MapSqlParameterSource("id", id));
        assertThat(row.get("last_seen_at")).isNull();
        assertThat(row.get("show_last_seen")).isEqualTo(true);
    }

    @Test
    void tempsDeReponse_medianeDesDecisionsDuVoyageurSurSesTrajets() throws Exception {
        UUID traveler = user();
        UUID sender = user();
        UUID tripId = trip(traveler);
        // Délais : 30 min, 60 min, 240 min → médiane 60.
        decidedBid(tripId, sender, traveler, "BID_ACCEPTED", 100, 70);
        decidedBid(tripId, sender, traveler, "BID_REJECTED", 200, 140);
        decidedBid(tripId, sender, traveler, "BID_ACCEPTED", 300, 60);
        // Bruit : action d'un autre acteur, autre action, décision trop ancienne.
        decidedBid(tripId, sender, sender, "BID_ACCEPTED", 50, 0);
        decidedBid(tripId, sender, traveler, "BID_CANCELLED", 50, 0);
        decidedBid(tripId, sender, traveler, "BID_ACCEPTED", 200_000, 199_000);

        Map<String, Object> row = stats(traveler);

        assertThat(((Number) row.get("decisions")).longValue()).isEqualTo(3);
        assertThat(((Number) row.get("medianMinutes")).doubleValue()).isBetween(59.0, 61.0);
    }

    @Test
    void tempsDeReponse_sansDecision_medianeNulle() throws Exception {
        Map<String, Object> row = stats(user());

        assertThat(((Number) row.get("decisions")).longValue()).isZero();
        assertThat(row.get("medianMinutes")).isNull();
    }
}
