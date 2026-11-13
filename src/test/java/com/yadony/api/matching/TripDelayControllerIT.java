package com.yadony.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Annonce de retard de bout en bout côté HTTP, sur PostgreSQL embarqué (Flyway complet, {@code ddl-auto=validate}) :
 * H2 ne sait pas poser le verrou {@code findByIdForUpdate} du trajet. Effets, refus, et {@code latestTripDelay}
 * dans les réponses. Chaque test a ses propres comptes : la base est partagée par toute la classe.
 */
@SpringBootTest
@ActiveProfiles("e2e")
@AutoConfigureMockMvc
@DisplayName("POST /announcements/{id}/delays")
class TripDelayControllerIT {

    private static final LocalDate DEPARTURE = LocalDate.now().minusDays(10);
    private static EmbeddedPostgres postgres;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired BidRepository bidRepository;
    @Autowired TripDelayRepository delayRepository;
    @Autowired JdbcTemplate jdbc;

    private UserEntity traveler;
    private UserEntity sender;
    private UserEntity otherTraveler;
    private AnnouncementEntity trip;

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
        String run = UUID.randomUUID().toString();
        traveler = persistUser("uid-delay-traveler-" + run, Role.TRAVELER);
        otherTraveler = persistUser("uid-delay-other-" + run, Role.TRAVELER);
        sender = persistUser("uid-delay-sender-" + run, Role.SENDER);
        trip = persistTrip(AnnouncementStatus.IN_PROGRESS, TransportMode.BOAT);
    }

    private static UsernamePasswordAuthenticationToken as(UserEntity user, String... roles) {
        return new UsernamePasswordAuthenticationToken(user.getFirebaseUid(), null,
                java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList());
    }

    private UsernamePasswordAuthenticationToken asTraveler(UserEntity user) {
        return as(user, "ROLE_TRAVELER");
    }

    private UserEntity persistUser(String uid, Role role) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setFirstName("Test");
        u.setLastName(uid);
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.VERIFIED);
        u.setRoles(new HashSet<>(List.of(role)));
        u.setTotalTrips(0);
        return userRepository.save(u);
    }

    private AnnouncementEntity persistTrip(AnnouncementStatus status, TransportMode mode) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(traveler.getId());
        a.setDepartureCity("Marseille");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(DEPARTURE);
        a.setArrivalDate(DEPARTURE.plusDays(20));
        if (mode == TransportMode.BOAT) {
            a.setArrivalDateMax(DEPARTURE.plusDays(30));
        }
        a.setTransportMode(mode);
        a.setPickupAddressLabel("Port de Marseille");
        a.setPickupLat(new BigDecimal("43.300000"));
        a.setPickupLng(new BigDecimal("5.370000"));
        a.setDeliveryAddressLabel("Port de Dakar");
        a.setDeliveryLat(new BigDecimal("14.690000"));
        a.setDeliveryLng(new BigDecimal("-17.440000"));
        a.setAvailableKg(new BigDecimal("10.00"));
        a.setTotalKg(new BigDecimal("10.00"));
        a.setPricePerKg(new BigDecimal("5.00"));
        a.setStatus(status);
        return announcementRepository.save(a);
    }

    private BidEntity persistBid(AnnouncementEntity a, BidStatus status, String code) {
        BidEntity b = new BidEntity();
        b.setAnnouncementId(a.getId());
        b.setSenderId(sender.getId());
        b.setWeightKg(new BigDecimal("3.00"));
        b.setStatus(status);
        b.setRecipientName("Fatou Diop");
        b.setRecipientPhone("+221 77 123 45 67");
        b.setTrackingNumber("DON-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
        b.setTrackingToken(UUID.randomUUID().toString());
        b.setConfirmationCode(code);
        b.setConfirmationCodeExpiry(DEPARTURE.plusDays(31).atStartOfDay());
        return bidRepository.save(b);
    }

    private List<TripDelayEntity> delaysOf(AnnouncementEntity a) {
        return delayRepository.findAll().stream().filter(d -> d.getAnnouncementId().equals(a.getId())).toList();
    }

    private static String body(LocalDate min, LocalDate max) {
        return """
                {"reason":"PORT_CONGESTION","arrivalDate":"%s","arrivalDateMax":"%s","message":"Quai saturé"}
                """.formatted(min, max);
    }

    private static String validBody() {
        return body(DEPARTURE.plusDays(40), DEPARTURE.plusDays(50));
    }

    @Test
    @DisplayName("201, ligne écrite, arrivée et codes de retrait mis à jour, colis livrés et annulés intacts")
    void announce_updatesTheTripAndTheEnRouteParcelsOnly() throws Exception {
        BidEntity handedOver = persistBid(trip, BidStatus.HANDED_OVER, "111111");
        BidEntity inTransit = persistBid(trip, BidStatus.IN_TRANSIT, "222222");
        BidEntity completed = persistBid(trip, BidStatus.COMPLETED, "333333");
        BidEntity cancelled = persistBid(trip, BidStatus.CANCELLED, "444444");
        LocalDateTime untouched = completed.getConfirmationCodeExpiry();

        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.reason").value("PORT_CONGESTION"))
                .andExpect(jsonPath("$.arrivalDate").value(DEPARTURE.plusDays(40).toString()))
                .andExpect(jsonPath("$.arrivalDateMax").value(DEPARTURE.plusDays(50).toString()))
                .andExpect(jsonPath("$.message").value("Quai saturé"))
                .andExpect(jsonPath("$.announcedAt").exists());

        AnnouncementEntity reloaded = announcementRepository.findById(trip.getId()).orElseThrow();
        assertThat(reloaded.getArrivalDate()).isEqualTo(DEPARTURE.plusDays(40));
        assertThat(reloaded.getArrivalDateMax()).isEqualTo(DEPARTURE.plusDays(50));
        LocalDateTime expected = ArrivalRules.pickupCodeExpiry(reloaded);
        assertThat(bidRepository.findById(handedOver.getId()).orElseThrow().getConfirmationCodeExpiry()).isEqualTo(expected);
        assertThat(bidRepository.findById(inTransit.getId()).orElseThrow().getConfirmationCodeExpiry()).isEqualTo(expected);
        assertThat(bidRepository.findById(completed.getId()).orElseThrow().getConfirmationCodeExpiry()).isEqualTo(untouched);
        assertThat(bidRepository.findById(cancelled.getId()).orElseThrow().getConfirmationCodeExpiry()).isEqualTo(untouched);

        TripDelayEntity row = delayRepository.findFirstByAnnouncementIdOrderByCreatedAtDesc(trip.getId()).orElseThrow();
        assertThat(row.getPreviousArrivalDate()).isEqualTo(DEPARTURE.plusDays(20));
        assertThat(row.getPreviousArrivalDateMax()).isEqualTo(DEPARTURE.plusDays(30));
        assertThat(row.getNewArrivalDateMax()).isEqualTo(DEPARTURE.plusDays(50));
        assertThat(row.getCreatedBy()).isEqualTo(traveler.getId());
    }

    @Test
    @DisplayName("l'audit TRIP_DELAY_ANNOUNCED est écrit une fois")
    void announce_writesTheAuditEntry() throws Exception {
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'TRIP_DELAY_ANNOUNCED' AND entity_id = ?",
                Integer.class, trip.getId());
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("hors bateau : seule l'arrivée au plus tôt est écrite sur l'annonce, la ligne garde la fourchette")
    void announce_onACar_keepsArrivalDateMaxNull() throws Exception {
        AnnouncementEntity car = persistTrip(AnnouncementStatus.IN_PROGRESS, TransportMode.CAR);
        car.setArrivalDate(DEPARTURE.plusDays(1));
        announcementRepository.save(car);

        mockMvc.perform(post("/announcements/{id}/delays", car.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(DEPARTURE.plusDays(3), DEPARTURE.plusDays(6))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.arrivalDateMax").value(DEPARTURE.plusDays(6).toString()));

        AnnouncementEntity reloaded = announcementRepository.findById(car.getId()).orElseThrow();
        assertThat(reloaded.getArrivalDate()).isEqualTo(DEPARTURE.plusDays(3));
        assertThat(reloaded.getArrivalDateMax()).isNull();
        assertThat(delayRepository.findFirstByAnnouncementIdOrderByCreatedAtDesc(car.getId()).orElseThrow()
                .getNewArrivalDateMax()).isEqualTo(DEPARTURE.plusDays(6));
    }

    @Test
    @DisplayName("trajet pas en cours : 409 trip-delay-not-allowed, en français et en anglais")
    void announce_beforeDeparture_409_localized() throws Exception {
        AnnouncementEntity active = persistTrip(AnnouncementStatus.ACTIVE, TransportMode.BOAT);

        mockMvc.perform(post("/announcements/{id}/delays", active.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("trip-delay-not-allowed"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("trajet en cours")));
        mockMvc.perform(post("/announcements/{id}/delays", active.getId()).with(authentication(asTraveler(traveler)))
                        .header("Accept-Language", "en-US")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("trip in progress")));
        assertThat(delaysOf(active)).isEmpty();
    }

    @Test
    @DisplayName("fin avant début : 422 arrival-range-invalid ; au-delà de 120 jours : 422 arrival-too-far")
    void announce_invalidRanges_422() throws Exception {
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(DEPARTURE.plusDays(40), DEPARTURE.plusDays(30))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("arrival-range-invalid"));
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(DEPARTURE.plusDays(100), DEPARTURE.plusDays(121))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("arrival-too-far"));
        assertThat(delaysOf(trip)).isEmpty();
    }

    @Test
    @DisplayName("corps incomplet : 422 de validation")
    void announce_missingFields_422() throws Exception {
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"arrivalDate\":\"" + DEPARTURE.plusDays(40) + "\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"WEATHER\",\"arrivalDate\":\"" + DEPARTURE.plusDays(40)
                                + "\",\"arrivalDateMax\":\"" + DEPARTURE.plusDays(41) + "\",\"message\":\""
                                + "x".repeat(301) + "\"}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("autre voyageur : 403 ; expéditeur seul : 403 ; sans jeton : refusé")
    void announce_byOtherUser_403() throws Exception {
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(otherTraveler)))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(as(sender, "ROLE_SENDER")))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId())
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().is4xxClientError());
        assertThat(delaysOf(trip)).isEmpty();
    }

    @Test
    @DisplayName("trajet inconnu : 404")
    void announce_unknownTrip_404() throws Exception {
        mockMvc.perform(post("/announcements/{id}/delays", UUID.randomUUID()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("latestTripDelay : null explicite sans retard, dernier retard ensuite, sur le colis et sur le trajet")
    void latestTripDelay_alwaysSerialized_onBidAndAnnouncement() throws Exception {
        BidEntity bid = persistBid(trip, BidStatus.HANDED_OVER, "111111");

        // Sans retard : la clé est là, nulle (détail, liste, mes trajets).
        mockMvc.perform(get("/bids/{id}", bid.getId()).with(authentication(as(sender, "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestTripDelay").value(nullValue()));
        assertThat(rawKeyPresent(mockMvc.perform(get("/bids/{id}", bid.getId())
                .with(authentication(as(sender, "ROLE_SENDER")))).andReturn(), "latestTripDelay")).isTrue();
        mockMvc.perform(get("/bids/me").with(authentication(as(sender, "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].latestTripDelay").value(nullValue()));
        MvcResult mine = mockMvc.perform(get("/announcements/my").with(authentication(asTraveler(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].latestTripDelay").value(nullValue()))
                .andReturn();
        assertThat(rawKeyPresent(mine, "content", 0, "latestTripDelay")).isTrue();

        // Deux retards : c'est le dernier qui est servi.
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                .contentType(MediaType.APPLICATION_JSON).content(body(DEPARTURE.plusDays(35), DEPARTURE.plusDays(45))))
                .andExpect(status().isCreated());
        Thread.sleep(15);
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated());

        String arrival = DEPARTURE.plusDays(40).toString();
        String arrivalMax = DEPARTURE.plusDays(50).toString();
        mockMvc.perform(get("/bids/{id}", bid.getId()).with(authentication(as(sender, "ROLE_SENDER"))))
                .andExpect(jsonPath("$.latestTripDelay.reason").value("PORT_CONGESTION"))
                .andExpect(jsonPath("$.latestTripDelay.arrivalDate").value(arrival))
                .andExpect(jsonPath("$.latestTripDelay.arrivalDateMax").value(arrivalMax))
                .andExpect(jsonPath("$.latestTripDelay.message").value("Quai saturé"))
                .andExpect(jsonPath("$.latestTripDelay.announcedAt").exists());
        mockMvc.perform(get("/bids/me").with(authentication(as(sender, "ROLE_SENDER"))))
                .andExpect(jsonPath("$[0].latestTripDelay.arrivalDateMax").value(arrivalMax));
        mockMvc.perform(get("/announcements/my").with(authentication(asTraveler(traveler))))
                .andExpect(jsonPath("$.content[0].latestTripDelay.arrivalDate").value(arrival))
                .andExpect(jsonPath("$.content[0].latestTripDelay.arrivalDateMax").value(arrivalMax));
    }

    private boolean rawKeyPresent(MvcResult result, Object... path) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        for (int i = 0; i < path.length - 1; i++) {
            node = path[i] instanceof Integer idx ? node.get(idx) : node.get((String) path[i]);
        }
        return node.has((String) path[path.length - 1]);
    }
}
