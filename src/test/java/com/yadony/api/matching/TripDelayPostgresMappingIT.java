package com.yadony.api.matching;

import com.yadony.api.matching.dto.TripDelayInfo;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TripDelayEntity} et {@link TripDelayRepository} sur PostgreSQL embarqué, toutes migrations Flyway et
 * {@code ddl-auto=validate} : le démarrage du contexte prouve que le mapping colle à {@code trip_delays} (V382),
 * puis un aller-retour relit chaque colonne, le soft delete masque la ligne, et les requêtes du dernier retard
 * (unitaire et groupée) rendent le plus récent.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Retards de trajet (V382, Postgres, validate)")
class TripDelayPostgresMappingIT {

    private static EmbeddedPostgres postgres;

    @Autowired TripDelayRepository delays;
    @Autowired AnnouncementRepository announcements;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (postgres != null) postgres.close();
    }

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> true);
    }

    private UUID user() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, firebase_uid, username) VALUES (?, ?, ?)",
                id, "uid-" + id, ("u" + id.toString().replace("-", "")).substring(0, 20));
        return id;
    }

    private UUID boatTrip(UUID travelerId) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setDepartureCity("Marseille");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().minusDays(5));
        a.setArrivalDate(LocalDate.now().plusDays(15));
        a.setArrivalDateMax(LocalDate.now().plusDays(25));
        a.setTransportMode(TransportMode.BOAT);
        a.setPickupAddressLabel("Port de Marseille");
        a.setPickupLat(new BigDecimal("43.300000"));
        a.setPickupLng(new BigDecimal("5.370000"));
        a.setDeliveryAddressLabel("Port de Dakar");
        a.setDeliveryLat(new BigDecimal("14.690000"));
        a.setDeliveryLng(new BigDecimal("-17.440000"));
        a.setCapacityUnit(CapacityUnit.KG_EXACT);
        a.setAvailableKg(new BigDecimal("10"));
        a.setTotalKg(new BigDecimal("10"));
        a.setPricePerKg(new BigDecimal("5"));
        a.setStatus(AnnouncementStatus.IN_PROGRESS);
        return announcements.saveAndFlush(a).getId();
    }

    private TripDelayEntity delay(UUID announcementId, UUID by, TripDelayReason reason, int minDays, int maxDays,
                                  String message) {
        TripDelayEntity d = new TripDelayEntity();
        d.setAnnouncementId(announcementId);
        d.setReason(reason);
        d.setPreviousArrivalDate(LocalDate.now().plusDays(15));
        d.setPreviousArrivalDateMax(LocalDate.now().plusDays(25));
        d.setNewArrivalDate(LocalDate.now().plusDays(minDays));
        d.setNewArrivalDateMax(LocalDate.now().plusDays(maxDays));
        d.setMessage(message);
        d.setCreatedBy(by);
        return d;
    }

    @Test
    @DisplayName("aller-retour : motif (enum texte), quatre dates, message de 300 caractères, auteur")
    void roundTrip() {
        UUID traveler = user();
        UUID trip = boatTrip(traveler);
        String longMessage = "m".repeat(300);

        UUID id = delays.saveAndFlush(delay(trip, traveler, TripDelayReason.EXTENDED_STOPOVER, 30, 40, longMessage)).getId();

        assertThat(jdbc.queryForObject("SELECT reason FROM trip_delays WHERE id = ?", String.class, id))
                .isEqualTo("EXTENDED_STOPOVER");
        TripDelayEntity reloaded = delays.findById(id).orElseThrow();
        assertThat(reloaded.getReason()).isEqualTo(TripDelayReason.EXTENDED_STOPOVER);
        assertThat(reloaded.getAnnouncementId()).isEqualTo(trip);
        assertThat(reloaded.getCreatedBy()).isEqualTo(traveler);
        assertThat(reloaded.getPreviousArrivalDate()).isEqualTo(LocalDate.now().plusDays(15));
        assertThat(reloaded.getPreviousArrivalDateMax()).isEqualTo(LocalDate.now().plusDays(25));
        assertThat(reloaded.getNewArrivalDate()).isEqualTo(LocalDate.now().plusDays(30));
        assertThat(reloaded.getNewArrivalDateMax()).isEqualTo(LocalDate.now().plusDays(40));
        assertThat(reloaded.getMessage()).isEqualTo(longMessage);
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertThat(TripDelayInfo.from(reloaded).announcedAt()).isNotNull();
    }

    @Test
    @DisplayName("chaque motif (dont TRANSPORT_DISRUPTION) tient dans la colonne, previous_* et message peuvent être nuls")
    void everyReasonAndNullablesRoundTrip() {
        UUID traveler = user();
        UUID trip = boatTrip(traveler);
        for (TripDelayReason reason : TripDelayReason.values()) {
            TripDelayEntity d = delay(trip, traveler, reason, 30, 31, null);
            d.setPreviousArrivalDate(null);
            d.setPreviousArrivalDateMax(null);
            TripDelayEntity reloaded = delays.findById(delays.saveAndFlush(d).getId()).orElseThrow();
            assertThat(reloaded.getReason()).isEqualTo(reason);
            assertThat(reloaded.getMessage()).isNull();
            assertThat(reloaded.getPreviousArrivalDate()).isNull();
            assertThat(reloaded.getPreviousArrivalDateMax()).isNull();
        }
    }

    @Test
    @DisplayName("fin de fourchette avant le début : refusée par chk_trip_delays_range")
    void rangeCheckIsEnforced() {
        UUID traveler = user();
        UUID trip = boatTrip(traveler);

        assertThatThrownBy(() -> delays.saveAndFlush(delay(trip, traveler, TripDelayReason.WEATHER, 40, 30, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_trip_delays_range");
    }

    @Test
    @DisplayName("soft delete : une ligne supprimée n'est ni relue ni dernière")
    void softDeletedRowIsHidden() {
        UUID traveler = user();
        UUID trip = boatTrip(traveler);
        UUID older = delays.saveAndFlush(delay(trip, traveler, TripDelayReason.WEATHER, 30, 35, null)).getId();
        UUID newer = delays.saveAndFlush(delay(trip, traveler, TripDelayReason.OTHER, 40, 45, null)).getId();
        assertThat(delays.findFirstByAnnouncementIdOrderByCreatedAtDesc(trip).orElseThrow().getId()).isEqualTo(newer);

        jdbc.update("UPDATE trip_delays SET deleted_at = ? WHERE id = ?", LocalDateTime.now(), newer);

        assertThat(delays.findById(newer)).isEmpty();
        assertThat(delays.findFirstByAnnouncementIdOrderByCreatedAtDesc(trip).orElseThrow().getId()).isEqualTo(older);
    }

    @Test
    @DisplayName("dernier retard : unitaire et groupé rendent le plus récent de chaque trajet, rien pour les autres")
    void latestPerAnnouncement_singleAndBatch() {
        UUID traveler = user();
        UUID tripA = boatTrip(traveler);
        UUID tripB = boatTrip(traveler);
        UUID tripWithout = boatTrip(traveler);
        delays.saveAndFlush(delay(tripA, traveler, TripDelayReason.WEATHER, 30, 35, "premier"));
        delays.saveAndFlush(delay(tripA, traveler, TripDelayReason.PORT_CONGESTION, 40, 50, "dernier A"));
        delays.saveAndFlush(delay(tripB, traveler, TripDelayReason.OTHER, 33, 36, "seul B"));

        assertThat(TripDelays.latest(delays, tripA).message()).isEqualTo("dernier A");
        assertThat(TripDelays.latest(delays, tripWithout)).isNull();
        assertThat(TripDelays.latest(delays, null)).isNull();
        assertThat(TripDelays.latest(null, tripA)).isNull();

        Map<UUID, TripDelayInfo> batch = TripDelays.latestByAnnouncement(delays,
                List.of(tripA, tripB, tripWithout, tripA));
        assertThat(batch).containsOnlyKeys(tripA, tripB);
        assertThat(batch.get(tripA).message()).isEqualTo("dernier A");
        assertThat(batch.get(tripA).reason()).isEqualTo("PORT_CONGESTION");
        assertThat(batch.get(tripB).message()).isEqualTo("seul B");

        assertThat(TripDelays.latestByAnnouncement(delays, List.of())).isEmpty();
        assertThat(TripDelays.latestByAnnouncement(null, List.of(tripA))).isEmpty();
    }

    @Test
    @DisplayName("lecture groupée bornée : une seule ligne par trajet quel que soit le nombre de retards, soft delete ignoré")
    void latestBatch_loadsOneRowPerTrip() {
        UUID traveler = user();
        UUID tripA = boatTrip(traveler);
        UUID tripB = boatTrip(traveler);
        for (int i = 0; i < 4; i++) {
            delays.saveAndFlush(delay(tripA, traveler, TripDelayReason.WEATHER, 30 + i, 40 + i, "A" + i));
            delays.saveAndFlush(delay(tripB, traveler, TripDelayReason.OTHER, 30 + i, 40 + i, "B" + i));
        }
        TripDelayEntity deletedNewest = delays.saveAndFlush(delay(tripB, traveler, TripDelayReason.OTHER, 50, 60, "B-supprimé"));
        jdbc.update("UPDATE trip_delays SET deleted_at = ? WHERE id = ?", LocalDateTime.now(), deletedNewest.getId());

        List<TripDelayEntity> latest = delays.findLatestByAnnouncementIdIn(List.of(tripA, tripB));

        assertThat(latest).hasSize(2);
        assertThat(latest).extracting(TripDelayEntity::getMessage).containsExactlyInAnyOrder("A3", "B3");
    }

    @Test
    @DisplayName("fenêtre de 24 h et retards d'un trajet : requêtes dérivées valides contre le schéma")
    void recentWindowAndPerTripQueries() {
        UUID traveler = user();
        UUID trip = boatTrip(traveler);
        UUID old = delays.saveAndFlush(delay(trip, traveler, TripDelayReason.WEATHER, 30, 35, "ancien")).getId();
        jdbc.update("UPDATE trip_delays SET created_at = ? WHERE id = ?", LocalDateTime.now().minusDays(2), old);
        UUID recent = delays.saveAndFlush(delay(trip, traveler, TripDelayReason.OTHER, 40, 45, "récent")).getId();

        assertThat(delays.findByAnnouncementIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
                trip, LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1)))
                .extracting(TripDelayEntity::getId).containsExactly(recent);
        assertThat(delays.findByAnnouncementId(trip)).hasSize(2);
    }

    @Test
    @DisplayName("lecture groupée : plus de BidResponsePrefetch.CHUNK_SIZE trajets, découpage sans perte")
    void latestByAnnouncement_overOneChunk() {
        UUID traveler = user();
        UUID trip = boatTrip(traveler);
        delays.saveAndFlush(delay(trip, traveler, TripDelayReason.WEATHER, 30, 35, "ok"));
        java.util.List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < BidResponsePrefetch.CHUNK_SIZE + 5; i++) {
            ids.add(UUID.randomUUID());
        }
        ids.add(trip);

        assertThat(TripDelays.latestByAnnouncement(delays, ids)).containsOnlyKeys(trip);
    }
}
