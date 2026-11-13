package com.yadony.api.matching;

import com.yadony.api.config.PlatformSettingKey;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.matching.dto.AnnouncementRequest;
import com.yadony.api.matching.dto.BidCheckoutRequest;
import com.yadony.api.matching.dto.BidNegotiationStartRequest;
import com.yadony.api.matching.dto.BidQuoteRequest;
import com.yadony.api.matching.dto.BidRequest;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.mock.web.MockHttpServletRequest;
import com.yadony.api.matching.dto.TripRescheduleRequest;
import com.yadony.api.payments.cash.PaymentMethod;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trajet bateau (lot 3, BA3) écrit par le service sur PostgreSQL embarqué, toutes migrations Flyway
 * et {@code ddl-auto=validate} : chaque écriture (création au m³ sans kilos, passage au kilo puis à
 * l'avion, report d'un bateau avec fourchette, modification d'un bateau d'avant le lot 3) respecte les
 * CHECK de V382 ({@code chk_announcements_kg_or_m3}, {@code chk_announcements_boat_columns},
 * {@code chk_announcements_arrival_date_max}, {@code chk_announcements_volume}).
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Trajet bateau au m³ sur PostgreSQL (V382, service)")
class BoatTripPostgresIT {

    private static final LocalDate DEP = LocalDate.now().plusDays(10);
    private static EmbeddedPostgres postgres;

    @Autowired AnnouncementService announcementService;
    @Autowired TripRescheduleService rescheduleService;
    @Autowired BidService bidService;
    @Autowired BidCheckoutService bidCheckoutService;
    @Autowired BidNegotiationService bidNegotiationService;
    @Autowired BidRepository bidRepository;
    @Autowired PlatformSettingsService platformSettings;
    @Autowired JdbcTemplate jdbc;

    private String uid;

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

    @BeforeEach
    void setUp() {
        UUID id = UUID.randomUUID();
        uid = "uid-boat-pg-" + id;
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, username, status, kyc_status, gp_pro, is_pro_account)
                VALUES (?, ?, ?, 'ACTIVE', 'VERIFIED', true, true)
                """, id, uid, ("b" + id.toString().replace("-", "")).substring(0, 20));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'TRAVELER')", id);
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "true"), UUID.randomUUID());
    }

    @AfterEach
    void closeBoat() {
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "false"), UUID.randomUUID());
    }

    private static AnnouncementRequest request(TransportMode mode, String availableKg, String pricePerKg,
                                               LocalDate arrival, LocalDate arrivalMax,
                                               String volume, String pricePerM3) {
        return new AnnouncementRequest("Marseille", "Dakar", DEP, LocalTime.of(10, 0), null,
                new AddressDto("Port de Marseille", 43.3, 5.37), new AddressDto("Port de Dakar", 14.69, -17.44),
                availableKg == null ? null : new BigDecimal(availableKg),
                pricePerKg == null ? null : new BigDecimal(pricePerKg),
                mode, null, null, null, Set.of(PaymentMethod.CASH), CapacityUnit.KG_EXACT, null,
                "FR", "SN", DEP.atTime(7, 30), false, false, "EUR", arrival, null, null, null,
                arrivalMax,
                volume == null ? null : new BigDecimal(volume),
                pricePerM3 == null ? null : new BigDecimal(pricePerM3));
    }

    private static AnnouncementRequest boatPerM3() {
        return request(TransportMode.BOAT, null, null, DEP.plusDays(20), DEP.plusDays(30), "2.5", "600");
    }

    private Map<String, Object> row(UUID id) {
        return jdbc.queryForMap("""
                SELECT transport_mode, available_kg, total_kg, price_per_kg, price_per_kg_eur, arrival_date,
                       arrival_date_max, volume_capacity_m3, available_volume_m3, price_per_m3
                FROM announcements WHERE id = ?
                """, id);
    }

    @Test
    @DisplayName("recherche : tri par prix décroissant sans trajet au m³ en tête (NULLS FIRST), filtres bateau")
    void search_priceSortDescending_keepsPerM3TripsLast_andBoatFilters() {
        UUID kg = announcementService.createAnnouncement(uid,
                request(TransportMode.PLANE, "10", "8", DEP.plusDays(1), null, null, null)).id();
        UUID cheap = announcementService.createAnnouncement(uid,
                request(TransportMode.BOAT, null, null, DEP.plusDays(20), DEP.plusDays(30), "2.5", "600")).id();
        UUID dear = announcementService.createAnnouncement(uid,
                request(TransportMode.BOAT, null, null, DEP.plusDays(35), DEP.plusDays(45), "0.5", "900")).id();
        Set<UUID> mine = Set.of(kg, cheap, dear);

        java.util.function.BiFunction<String, AnnouncementSearchExtras, java.util.List<UUID>> search =
                (dir, extras) -> announcementService.searchAnnouncements(
                                null, null, null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null,
                                "price", dir, org.springframework.data.domain.PageRequest.of(0, 200), null, null, extras)
                        .getContent().stream().map(com.yadony.api.matching.dto.AnnouncementSearchResponse::id)
                        .filter(mine::contains).toList();

        // Le trajet au kilo (pivot non nul) vient avant les trajets au m³ (pivot nul), dans les deux sens.
        assertThat(search.apply("desc", AnnouncementSearchExtras.NONE).get(0)).isEqualTo(kg);
        assertThat(search.apply("asc", AnnouncementSearchExtras.NONE).get(0)).isEqualTo(kg);

        AnnouncementSearchExtras volume = new AnnouncementSearchExtras(null, Set.of(), false, false, null, null,
                new BigDecimal("1"), null, null);
        AnnouncementSearchExtras price = new AnnouncementSearchExtras(null, Set.of(), false, false, null, null,
                null, new BigDecimal("700"), null);
        AnnouncementSearchExtras arrival = new AnnouncementSearchExtras(null, Set.of(), false, false, null, null,
                null, null, DEP.plusDays(30));
        assertThat(search.apply("desc", volume)).containsExactly(cheap);
        assertThat(search.apply("desc", price)).containsExactly(cheap);
        assertThat(search.apply("desc", arrival)).containsExactlyInAnyOrder(kg, cheap);
    }

    @Test
    @DisplayName("création au m³ : aucun kilo en base, volume restant = proposé, fourchette écrite")
    void createPerM3_noKgInDatabase() {
        UUID id = announcementService.createAnnouncement(uid, boatPerM3()).id();

        Map<String, Object> r = row(id);
        assertThat(r.get("available_kg")).isNull();
        assertThat(r.get("total_kg")).isNull();
        assertThat(r.get("price_per_kg")).isNull();
        assertThat(r.get("price_per_kg_eur")).isNull();
        assertThat((BigDecimal) r.get("volume_capacity_m3")).isEqualByComparingTo("2.5");
        assertThat((BigDecimal) r.get("available_volume_m3")).isEqualByComparingTo("2.5");
        assertThat((BigDecimal) r.get("price_per_m3")).isEqualByComparingTo("600");
        assertThat(r.get("arrival_date_max").toString()).isEqualTo(DEP.plusDays(30).toString());
    }

    @Test
    @DisplayName("m³ puis kilo puis avion : chaque modification respecte les CHECK, l'avion n'a plus rien du bateau")
    void editPerM3ToPerKgToPlane() {
        UUID id = announcementService.createAnnouncement(uid, boatPerM3()).id();

        announcementService.updateAnnouncement(id, uid,
                request(TransportMode.BOAT, "500", "4", DEP.plusDays(20), DEP.plusDays(35), "3", null));
        Map<String, Object> perKg = row(id);
        assertThat((BigDecimal) perKg.get("available_kg")).isEqualByComparingTo("500");
        assertThat(perKg.get("price_per_m3")).isNull();
        assertThat((BigDecimal) perKg.get("available_volume_m3")).isEqualByComparingTo("3");

        announcementService.updateAnnouncement(id, uid,
                request(TransportMode.PLANE, "20", "9", DEP.plusDays(1), null, null, null));
        Map<String, Object> plane = row(id);
        assertThat(plane.get("transport_mode")).isEqualTo("PLANE");
        assertThat(plane.get("arrival_date_max")).isNull();
        assertThat(plane.get("volume_capacity_m3")).isNull();
        assertThat(plane.get("available_volume_m3")).isNull();
        assertThat((BigDecimal) plane.get("price_per_kg")).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("report d'un bateau à fourchette : la fourchette glisse avec l'arrivée")
    void reschedulePerM3_shiftsRange() {
        UUID id = announcementService.createAnnouncement(uid, boatPerM3()).id();
        LocalDate newDeparture = DEP.plusDays(5);

        rescheduleService.reschedule(id, uid, new TripRescheduleRequest(newDeparture, LocalTime.of(10, 0),
                newDeparture.plusDays(20), null, newDeparture.atTime(7, 30), TripRescheduleReason.POSTPONED, null));

        Map<String, Object> r = row(id);
        assertThat(r.get("arrival_date").toString()).isEqualTo(newDeparture.plusDays(20).toString());
        assertThat(r.get("arrival_date_max").toString()).isEqualTo(newDeparture.plusDays(30).toString());
    }

    @Test
    @DisplayName("bateau d'avant le lot 3 (au kilo, sans fourchette) modifié sans champ bateau : reste tel quel")
    void editLegacyBoatWithoutBoatFields() {
        UUID userId = jdbc.queryForObject("SELECT id FROM users WHERE firebase_uid = ?", UUID.class, uid);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO announcements (id, traveler_id, departure_city, arrival_city, departure_date,
                  departure_time, available_kg, price_per_kg, transport_mode, total_kg, currency, timezone,
                  pickup_address_label, pickup_lat, pickup_lng, delivery_address_label, delivery_lat, delivery_lng,
                  status, created_at, updated_at)
                VALUES (?, ?, 'Marseille', 'Dakar', ?, '10:00', 10.00, 5.00, 'BOAT', 10.00, 'EUR', 'Europe/Paris',
                  'Port', 43.3, 5.37, 'Port', 14.69, -17.44, 'ACTIVE', now(), now())
                """, id, userId, DEP);

        announcementService.updateAnnouncement(id, uid,
                request(TransportMode.BOAT, "12", "5", DEP.plusDays(25), null, null, null));

        Map<String, Object> r = row(id);
        assertThat(r.get("arrival_date_max")).isNull();
        assertThat((BigDecimal) r.get("available_kg")).isEqualByComparingTo("12");
        assertThat(r.get("arrival_date").toString()).isEqualTo(DEP.plusDays(25).toString());
    }

    private String sender() {
        UUID id = UUID.randomUUID();
        String senderUid = "uid-boat-pg-sender-" + id;
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, username, status, kyc_status)
                VALUES (?, ?, ?, 'ACTIVE', 'VERIFIED')
                """, id, senderUid, ("s" + id.toString().replace("-", "")).substring(0, 20));
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, 'SENDER')", id);
        return senderUid;
    }

    private static void assertVolumeBookingRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
            assertThat(ex.getStatus()).isEqualTo(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(ex.getErrorCode()).isEqualTo(BoatVolumeBooking.ERROR_CODE);
        });
    }

    @Test
    @DisplayName("trajet au m³ : offre, paiement carte, devis et proposition de prix refusés en 422, aucun colis créé")
    void bookingPerM3Trip_refusedEverywhereBeforeKg() {
        AnnouncementRequest negotiable = new AnnouncementRequest("Marseille", "Dakar", DEP, LocalTime.of(10, 0), null,
                new AddressDto("Port de Marseille", 43.3, 5.37), new AddressDto("Port de Dakar", 14.69, -17.44),
                null, null, TransportMode.BOAT, null, null, null, Set.of(PaymentMethod.CASH), CapacityUnit.KG_EXACT,
                null, "FR", "SN", DEP.atTime(7, 30), false, true, "EUR", DEP.plusDays(20), null, null, null,
                DEP.plusDays(30), new BigDecimal("2.5"), new BigDecimal("600"));
        UUID tripId = announcementService.createAnnouncement(uid, negotiable).id();
        String senderUid = sender();
        BigDecimal two = new BigDecimal("2");

        assertVolumeBookingRefused(() -> bidService.createBid(tripId, senderUid,
                new BidRequest(two, "Vêtements", "OTHER", "Awa", "+221771234567", true,
                        null, null, null, null, null, null),
                new MockHttpServletRequest()));
        assertVolumeBookingRefused(() -> bidCheckoutService.checkout(senderUid,
                new BidCheckoutRequest(tripId, two, "Vêtements", "OTHER", "Awa", "+221771234567", true,
                        null, null),
                new MockHttpServletRequest()));
        assertVolumeBookingRefused(() -> bidService.quote(senderUid, new BidQuoteRequest(tripId, two, null, null)));
        assertVolumeBookingRefused(() -> bidNegotiationService.propose(tripId, senderUid,
                new BidNegotiationStartRequest(two, "Vêtements", "OTHER", "Awa", "+221771234567", true,
                        null, null, null, null, new BigDecimal("50"), null, null),
                new MockHttpServletRequest()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bids WHERE announcement_id = ?", Long.class, tripId))
                .isZero();
    }
}
