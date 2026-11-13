package com.yadony.api.requests.service;

import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.config.PlatformSettingKey;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.TransportMode;
import com.yadony.api.matching.dto.AddressDto;
import com.yadony.api.requests.dto.NegotiationCreateDedicatedTripRequest;
import com.yadony.api.requests.dto.NegotiationStartRequest;
import com.yadony.api.requests.dto.NegotiationThreadResponse;
import com.yadony.api.requests.entity.PackageRequestEntity;
import com.yadony.api.requests.repository.PackageRequestRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Trajet dédié d'une négociation en bateau (lot 3, BA7) sur PostgreSQL embarqué, toutes migrations
 * Flyway et {@code ddl-auto=validate} : la forme « au m³ » du trajet (volume de la demande, prix au m³
 * dérivé, aucun kilo) respecte les CHECK de V382, la garde « transporteur vérifié » (BA2) s'applique, la
 * fourchette d'arrivée est obligatoire, et l'offre (début de fil comme trajet d'un fil en attente) écrit
 * le trajet dédié au m³ et le fil, la réservation au volume existant (BB2) : le prix du fil reste le prix
 * total du colis, le prix au m³ n'en est que la dérivée.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@DisplayName("Trajet dédié bateau au m³ sur PostgreSQL (V382, négociation)")
class NegotiationDedicatedTripBoatIT {

    private static final LocalDate DEP = LocalDate.now().plusDays(10);
    private static EmbeddedPostgres postgres;

    @Autowired NegotiationService negotiationService;
    @Autowired PackageRequestRepository requestRepo;
    @Autowired com.yadony.api.auth.UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepo;
    @Autowired PlatformSettingsService platformSettings;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.yadony.api.requests.repository.NegotiationThreadRepository threadRepo;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private UUID senderId;
    private UUID travelerId;

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
        senderId = insertUser("S", "SENDER", false);
        travelerId = insertUser("T", "TRAVELER", true);
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "true"), UUID.randomUUID());
    }

    @AfterEach
    void closeBoat() {
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "false"), UUID.randomUUID());
    }

    private UUID insertUser(String prefix, String role, boolean gpPro) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, firebase_uid, username, status, kyc_status, gp_pro, is_pro_account)
                VALUES (?, ?, ?, 'ACTIVE', 'VERIFIED', ?, false)
                """, id, "uid-ded-" + id, (prefix + id.toString().replace("-", "")).substring(0, 20), gpPro);
        jdbc.update("INSERT INTO user_roles (user_id, role) VALUES (?, ?)", id, role);
        return id;
    }

    private void setBadges(boolean gpPro) {
        jdbc.update("UPDATE users SET gp_pro = ? WHERE id = ?", gpPro, travelerId);
    }

    private PackageRequestEntity boatRequest(String volumeM3) {
        PackageRequestEntity request = new PackageRequestEntity();
        request.setSenderId(senderId);
        request.setDepartureCity("Marseille");
        request.setArrivalCity("Dakar");
        request.setDesiredDate(DEP);
        request.setDateToleranceDays((short) 2);
        request.setWeightKg(new BigDecimal("40"));
        request.setParcelSize(com.yadony.api.requests.entity.ParcelSize.LARGE);
        request.setContentCategory("CLOTHES");
        request.setStatus(com.yadony.api.requests.entity.PackageRequestStatus.OPEN);
        request.setCurrency("EUR");
        request.setTransportMode(TransportMode.BOAT);
        request.setVolumeM3(volumeM3 == null ? null : new BigDecimal(volumeM3));
        request.setAcceptedPaymentMethods(java.util.EnumSet.of(com.yadony.api.payments.cash.PaymentMethod.CASH));
        return requestRepo.save(request);
    }

    private static NegotiationCreateDedicatedTripRequest boatTrip(LocalDate arrivalMax) {
        return new NegotiationCreateDedicatedTripRequest(
                DEP, null, null,
                new AddressDto("Port de Marseille", 43.3, 5.37),
                new AddressDto("Port de Dakar", 14.69, -17.44),
                "Groupage", List.of("vetements"), List.of(), false,
                DEP.plusDays(20), null, arrivalMax);
    }

    private NegotiationThreadResponse start(UUID requestId, NegotiationCreateDedicatedTripRequest trip) {
        return negotiationService.start(travelerId, new NegotiationStartRequest(
                requestId, new BigDecimal("500"), DEP, new BigDecimal("40"), null, null, true, trip));
    }

    private long dedicatedTrips(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM announcements WHERE linked_package_request_id = ?", Long.class, requestId);
    }

    private long threads(UUID requestId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM negotiation_threads WHERE package_request_id = ?", Long.class, requestId);
    }

    @Test
    @DisplayName("dedicatedBoatTrip_copiesRequestVolume : la forme au m³ passe les CHECK de V382 et se relit telle quelle")
    void dedicatedBoatTrip_copiesRequestVolume() {
        PackageRequestEntity request = boatRequest("0.800");

        AnnouncementEntity built = negotiationService.buildDedicatedTripAnnouncement(
                request, userRepository.findById(travelerId).orElseThrow(), new BigDecimal("500"), boatTrip(DEP.plusDays(30)));
        AnnouncementEntity saved = announcementRepo.saveAndFlush(built);

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT transport_mode, available_kg, total_kg, price_per_kg, price_per_kg_eur, reserved_kg,
                       arrival_date, arrival_date_max, volume_capacity_m3, available_volume_m3, price_per_m3
                FROM announcements WHERE id = ?
                """, saved.getId());
        assertThat(row.get("transport_mode")).isEqualTo("BOAT");
        assertThat(row.get("available_kg")).isNull();
        assertThat(row.get("total_kg")).isNull();
        assertThat(row.get("price_per_kg")).isNull();
        assertThat(row.get("price_per_kg_eur")).isNull();
        assertThat((BigDecimal) row.get("reserved_kg")).isEqualByComparingTo("0");
        assertThat((BigDecimal) row.get("volume_capacity_m3")).isEqualByComparingTo("0.800");
        assertThat((BigDecimal) row.get("available_volume_m3")).isEqualByComparingTo("0.800");
        assertThat((BigDecimal) row.get("price_per_m3")).isEqualByComparingTo("625.00");
        assertThat(row.get("arrival_date")).isNotNull();
        assertThat(row.get("arrival_date_max")).isNotNull();
    }

    @Test
    @DisplayName("start : trajet dédié bateau au m³ écrit avec le fil, prix du fil = prix total convenu")
    void start_dedicatedBoat_writesPerM3TripAndThread() {
        PackageRequestEntity request = boatRequest("0.800");

        NegotiationThreadResponse thread = start(request.getId(), boatTrip(DEP.plusDays(30)));

        assertThat(dedicatedTrips(request.getId())).isEqualTo(1);
        assertThat(threads(request.getId())).isEqualTo(1);
        Map<String, Object> trip = jdbc.queryForMap("""
                SELECT available_kg, volume_capacity_m3, available_volume_m3, price_per_m3
                FROM announcements WHERE linked_package_request_id = ?
                """, request.getId());
        assertThat(trip.get("available_kg")).isNull();
        assertThat((BigDecimal) trip.get("volume_capacity_m3")).isEqualByComparingTo("0.800");
        assertThat((BigDecimal) trip.get("available_volume_m3")).isEqualByComparingTo("0.800");
        assertThat((BigDecimal) trip.get("price_per_m3")).isEqualByComparingTo("625.00");
        assertThat(jdbc.queryForObject("SELECT current_price_eur FROM negotiation_threads WHERE id = ?",
                BigDecimal.class, thread.id())).isEqualByComparingTo("500");
    }

    @Test
    @DisplayName("start : 1 litre à 100 000 € → prix au m³ dérivé hors NUMERIC(10,2), 422 volume-price-too-high, rien d'écrit")
    void start_dedicatedBoat_derivedPricePerM3TooHigh_422() {
        PackageRequestEntity request = boatRequest("0.001");

        assertThatThrownBy(() -> negotiationService.start(travelerId, new NegotiationStartRequest(
                request.getId(), new BigDecimal("100000"), DEP, new BigDecimal("40"), null, null, true,
                boatTrip(DEP.plusDays(30)))))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("volume-price-too-high");
                });

        assertThat(dedicatedTrips(request.getId())).isZero();
        assertThat(threads(request.getId())).isZero();
    }

    @Test
    @DisplayName("garde BA2 : voyageur non vérifié (ni GP Pro ni PRO) → 403 boat-carrier-not-verified, rien d'écrit")
    void start_dedicatedBoat_unverifiedCarrier_403() {
        setBadges(false);
        PackageRequestEntity request = boatRequest("0.800");

        assertThatThrownBy(() -> start(request.getId(), boatTrip(DEP.plusDays(30))))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getErrorCode()).isEqualTo("boat-carrier-not-verified");
                });

        assertThat(dedicatedTrips(request.getId())).isZero();
        assertThat(threads(request.getId())).isZero();
    }

    @Test
    @DisplayName("dedicatedBoatTrip_withoutArrivalMax_422 : fourchette d'arrivée obligatoire")
    void dedicatedBoatTrip_withoutArrivalMax_422() {
        PackageRequestEntity request = boatRequest("0.800");

        assertThatThrownBy(() -> start(request.getId(), boatTrip(null)))
                .isInstanceOfSatisfying(YadonyBusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(ex.getErrorCode()).isEqualTo("arrival-range-invalid");
                });

        assertThat(dedicatedTrips(request.getId())).isZero();
        assertThat(threads(request.getId())).isZero();
    }

    @Test
    @DisplayName("demande bateau sans volume → 422 boat-dimensions-required")
    void start_dedicatedBoat_requestWithoutVolume_422() {
        PackageRequestEntity request = boatRequest(null);

        assertThatThrownBy(() -> start(request.getId(), boatTrip(DEP.plusDays(30))))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        ex -> assertThat(ex.getErrorCode()).isEqualTo("boat-dimensions-required"));

        assertThat(dedicatedTrips(request.getId())).isZero();
    }

    @Test
    @DisplayName("createDedicatedTrip : fil AWAITING_TRIP bateau → trajet dédié au m³ écrit et lié, fil en attente de paiement")
    void createDedicatedTrip_boat_linksPerM3Trip() {
        PackageRequestEntity request = boatRequest("0.800");
        com.yadony.api.requests.entity.NegotiationThreadEntity thread =
                new com.yadony.api.requests.entity.NegotiationThreadEntity();
        thread.setPackageRequestId(request.getId());
        thread.setTravelerId(travelerId);
        thread.setTravelerTravelDate(DEP);
        thread.setTravelerAvailableKg(new BigDecimal("40"));
        thread.setStatus(com.yadony.api.requests.entity.NegotiationThreadStatus.AWAITING_TRIP);
        thread.setCurrentPriceEur(new BigDecimal("500"));
        thread.setRoundsCount((short) 1);
        thread.setLastActivityAt(java.time.LocalDateTime.now());
        UUID threadId = threadRepo.saveAndFlush(thread).getId();

        negotiationService.createDedicatedTrip(travelerId, threadId, boatTrip(DEP.plusDays(30)));

        assertThat(dedicatedTrips(request.getId())).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, traveler_announcement_id FROM negotiation_threads WHERE id = ?", threadId);
        assertThat(row.get("status")).isEqualTo("AWAITING_PAYMENT");
        assertThat(row.get("traveler_announcement_id")).isNotNull();
        assertThat((BigDecimal) jdbc.queryForObject("SELECT price_per_m3 FROM announcements WHERE id = ?",
                BigDecimal.class, row.get("traveler_announcement_id"))).isEqualByComparingTo("625.00");
    }

    @Test
    @DisplayName("le fil sans mode sérialise la clé transportMode à null avec l'ObjectMapper du contexte (NON_NULL global)")
    void threadResponse_nullMode_keyPresentWithSpringObjectMapper() throws Exception {
        PackageRequestEntity request = boatRequest("0.800");
        request.setTransportMode(null);
        com.yadony.api.requests.entity.NegotiationThreadEntity thread =
                new com.yadony.api.requests.entity.NegotiationThreadEntity();
        thread.setPackageRequestId(request.getId());
        thread.setTravelerId(travelerId);
        thread.setTravelerTravelDate(DEP);
        thread.setTravelerAvailableKg(new BigDecimal("40"));
        thread.setStatus(com.yadony.api.requests.entity.NegotiationThreadStatus.OPEN);
        thread.setCurrentPriceEur(new BigDecimal("500"));
        thread.setRoundsCount((short) 1);
        thread.setLastActivityAt(java.time.LocalDateTime.now());

        NegotiationThreadResponse response = negotiationService.toResponse(
                thread, List.of(), null, userRepository.findById(travelerId).orElseThrow(), request,
                travelerId, "Expéditeur", null);

        com.fasterxml.jackson.databind.JsonNode node = objectMapper.valueToTree(response);
        assertThat(node.has("transportMode")).isTrue();
        assertThat(node.get("transportMode").isNull()).isTrue();
    }
}
