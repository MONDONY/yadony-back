package com.yadony.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.matching.events.TripDelayedEvent;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
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
@RecordApplicationEvents
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
    @Autowired ApplicationEvents events;

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
        return body("PORT_CONGESTION", min, max);
    }

    private static String body(String reason, LocalDate min, LocalDate max) {
        return """
                {"reason":"%s","arrivalDate":"%s","arrivalDateMax":"%s","message":"Quai saturé"}
                """.formatted(reason, min, max);
    }

    /** Trajet routier parti il y a deux jours (les dates de retard restent dans le futur proche). */
    private AnnouncementEntity persistCar() {
        AnnouncementEntity car = persistTrip(AnnouncementStatus.IN_PROGRESS, TransportMode.CAR);
        LocalDate carDeparture = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2);
        car.setDepartureDate(carDeparture);
        car.setArrivalDate(carDeparture.plusDays(1));
        return announcementRepository.save(car);
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
        BidEntity arrived = persistBid(trip, BidStatus.ARRIVED, "555555");
        BidEntity cancelled = persistBid(trip, BidStatus.CANCELLED, "444444");
        BidEntity pending = persistBid(trip, BidStatus.PENDING, "666666");
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
        assertThat(bidRepository.findById(arrived.getId()).orElseThrow().getConfirmationCodeExpiry()).isEqualTo(expected);
        assertThat(bidRepository.findById(cancelled.getId()).orElseThrow().getConfirmationCodeExpiry()).isEqualTo(untouched);
        assertThat(bidRepository.findById(pending.getId()).orElseThrow().getConfirmationCodeExpiry()).isEqualTo(untouched);

        // Cibles de l'événement : exactement les colis en route, ni le livré, ni l'annulé, ni l'en attente.
        List<TripDelayedEvent> published = events.stream(TripDelayedEvent.class).toList();
        assertThat(published).hasSize(1);
        assertThat(published.get(0).targets()).extracting(TripDelayedEvent.Target::bidId)
                .containsExactlyInAnyOrder(handedOver.getId(), inTransit.getId(), arrived.getId());

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
        AnnouncementEntity car = persistCar();
        LocalDate carDeparture = car.getDepartureDate();
        BidEntity inTransit = persistBid(car, BidStatus.IN_TRANSIT, "777777");

        mockMvc.perform(post("/announcements/{id}/delays", car.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("TRANSPORT_DISRUPTION", carDeparture.plusDays(3), carDeparture.plusDays(12))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reason").value("TRANSPORT_DISRUPTION"))
                .andExpect(jsonPath("$.arrivalDateMax").value(carDeparture.plusDays(12).toString()));

        AnnouncementEntity reloaded = announcementRepository.findById(car.getId()).orElseThrow();
        assertThat(reloaded.getArrivalDate()).isEqualTo(carDeparture.plusDays(3));
        assertThat(reloaded.getArrivalDateMax()).isNull();
        assertThat(delayRepository.findFirstByAnnouncementIdOrderByCreatedAtDesc(car.getId()).orElseThrow()
                .getNewArrivalDateMax()).isEqualTo(carDeparture.plusDays(12));
        // Le code de retrait est calé sur la FIN annoncée, pas sur le début que l'annonce garde seul.
        assertThat(bidRepository.findById(inTransit.getId()).orElseThrow().getConfirmationCodeExpiry())
                .isEqualTo(carDeparture.plusDays(12).atStartOfDay().plusDays(3));
    }

    @Test
    @DisplayName("hors bateau : fin au-delà de la durée du mode : 422 arrival-too-far ; PORT_CONGESTION : 422 trip-delay-reason-invalid")
    void announce_onACar_endBeyondTheModeAndBoatOnlyReason_422() throws Exception {
        AnnouncementEntity car = persistCar();
        LocalDate carDeparture = car.getDepartureDate();

        mockMvc.perform(post("/announcements/{id}/delays", car.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("TRANSPORT_DISRUPTION", carDeparture.plusDays(3), carDeparture.plusDays(15))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("arrival-too-far"));
        mockMvc.perform(post("/announcements/{id}/delays", car.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("PORT_CONGESTION", carDeparture.plusDays(3), carDeparture.plusDays(4))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("trip-delay-reason-invalid"));
        assertThat(delaysOf(car)).isEmpty();
    }

    @Test
    @DisplayName("fourchette entièrement passée : 422 arrival-range-invalid, rien d'écrit, codes intacts")
    void announce_rangeInThePast_422() throws Exception {
        BidEntity handedOver = persistBid(trip, BidStatus.HANDED_OVER, "111111");
        LocalDateTime untouched = handedOver.getConfirmationCodeExpiry();

        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(DEPARTURE.plusDays(1), DEPARTURE.plusDays(2))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("arrival-range-invalid"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("aujourd'hui")));

        assertThat(delaysOf(trip)).isEmpty();
        assertThat(bidRepository.findById(handedOver.getId()).orElseThrow().getConfirmationCodeExpiry())
                .isEqualTo(untouched);
        assertThat(announcementRepository.findById(trip.getId()).orElseThrow().getArrivalDate())
                .isEqualTo(DEPARTURE.plusDays(20));
    }

    @Test
    @DisplayName("double appui : même retard renvoyé (201), une ligne, un événement, un audit")
    void announce_twiceInARow_isIdempotent() throws Exception {
        persistBid(trip, BidStatus.HANDED_OVER, "111111");

        String first = mockMvc.perform(post("/announcements/{id}/delays", trip.getId())
                        .with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/announcements/{id}/delays", trip.getId())
                        .with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("id")).isEqualTo(objectMapper.readTree(first).get("id"));
        assertThat(delaysOf(trip)).hasSize(1);
        assertThat(events.stream(TripDelayedEvent.class).count()).isEqualTo(1);
        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'TRIP_DELAY_ANNOUNCED' AND entity_id = ?",
                Integer.class, trip.getId());
        assertThat(audits).isEqualTo(1);
    }

    @Test
    @DisplayName("au-delà de 5 retards en 24 h : 409 trip-delay-too-frequent, fr et en")
    void announce_sixthInADay_409() throws Exception {
        for (int i = 0; i < TripDelayService.MAX_DELAYS_PER_DAY; i++) {
            mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(DEPARTURE.plusDays(31 + i), DEPARTURE.plusDays(41 + i))))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(DEPARTURE.plusDays(60), DEPARTURE.plusDays(70))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("trip-delay-too-frequent"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("5 retards")));
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                        .header("Accept-Language", "en-US")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(DEPARTURE.plusDays(60), DEPARTURE.plusDays(70))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("5 delays")));
        assertThat(delaysOf(trip)).hasSize(TripDelayService.MAX_DELAYS_PER_DAY);
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
                .andExpect(status().isUnauthorized());
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

    @Test
    @DisplayName("latestTripDelay du colis : servi aux colis en route et livrés, null pour un colis en attente")
    void latestTripDelay_isNullForParcelsTheDelayDoesNotConcern() throws Exception {
        BidEntity pending = persistBid(trip, BidStatus.PENDING, null);
        BidEntity handedOver = persistBid(trip, BidStatus.HANDED_OVER, "111111");
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                .contentType(MediaType.APPLICATION_JSON).content(validBody())).andExpect(status().isCreated());

        mockMvc.perform(get("/bids/{id}", handedOver.getId()).with(authentication(as(sender, "ROLE_SENDER"))))
                .andExpect(jsonPath("$.latestTripDelay.reason").value("PORT_CONGESTION"));
        mockMvc.perform(get("/bids/{id}", pending.getId()).with(authentication(as(sender, "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestTripDelay").value(nullValue()));
    }

    @Test
    @DisplayName("BidResponse.arrivalDateMax : la fin de fourchette d'un bateau, toujours sérialisée, null hors bateau")
    void bidResponse_servesArrivalDateMax() throws Exception {
        BidEntity boatBid = persistBid(trip, BidStatus.HANDED_OVER, "111111");
        AnnouncementEntity car = persistCar();
        BidEntity carBid = persistBid(car, BidStatus.HANDED_OVER, "222222");

        MvcResult boat = mockMvc.perform(get("/bids/{id}", boatBid.getId())
                        .with(authentication(as(sender, "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivalDate").value(DEPARTURE.plusDays(20).toString()))
                .andExpect(jsonPath("$.arrivalDateMax").value(DEPARTURE.plusDays(30).toString()))
                .andReturn();
        assertThat(rawKeyPresent(boat, "arrivalDateMax")).isTrue();
        MvcResult road = mockMvc.perform(get("/bids/{id}", carBid.getId())
                        .with(authentication(as(sender, "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.arrivalDateMax").value(nullValue()))
                .andReturn();
        assertThat(rawKeyPresent(road, "arrivalDateMax")).as("clé toujours servie, nulle").isTrue();
    }

    @Test
    @DisplayName("fiche du trajet : latestTripDelay servi au seul voyageur propriétaire")
    void announcementDetail_latestTripDelay_ownerOnly() throws Exception {
        persistBid(trip, BidStatus.HANDED_OVER, "111111");
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                .contentType(MediaType.APPLICATION_JSON).content(validBody())).andExpect(status().isCreated());

        mockMvc.perform(get("/announcements/{id}", trip.getId()).with(authentication(asTraveler(traveler))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestTripDelay.reason").value("PORT_CONGESTION"))
                .andExpect(jsonPath("$.latestTripDelay.arrivalDateMax").value(DEPARTURE.plusDays(50).toString()))
                .andExpect(jsonPath("$.latestTripDelay.message").value("Quai saturé"));
        for (UsernamePasswordAuthenticationToken other : List.of(
                as(sender, "ROLE_SENDER"), asTraveler(otherTraveler))) {
            MvcResult res = mockMvc.perform(get("/announcements/{id}", trip.getId()).with(authentication(other)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.latestTripDelay").value(nullValue()))
                    .andReturn();
            assertThat(rawKeyPresent(res, "latestTripDelay")).as("clé toujours servie").isTrue();
            assertThat(res.getResponse().getContentAsString()).doesNotContain("Quai saturé");
        }
    }

    @Test
    @DisplayName("un report efface le retard annoncé : latestTripDelay redevient nul, la ligne reste en base (soft delete)")
    void reschedule_clearsTheAnnouncedDelay() throws Exception {
        BidEntity bid = persistBid(trip, BidStatus.HANDED_OVER, "111111");
        mockMvc.perform(post("/announcements/{id}/delays", trip.getId()).with(authentication(asTraveler(traveler)))
                .contentType(MediaType.APPLICATION_JSON).content(validBody())).andExpect(status().isCreated());
        mockMvc.perform(get("/bids/{id}", bid.getId()).with(authentication(as(sender, "ROLE_SENDER"))))
                .andExpect(jsonPath("$.latestTripDelay.reason").value("PORT_CONGESTION"));

        LocalDate newDeparture = LocalDate.now().plusDays(10);
        String reschedule = """
                {"departureDate":"%s","departureTime":"10:00","arrivalDate":"%s","handoverDeadline":"%sT18:00:00",
                 "reason":"POSTPONED"}
                """.formatted(newDeparture, newDeparture.plusDays(25), newDeparture.minusDays(1));
        mockMvc.perform(post("/announcements/{id}/reschedule", trip.getId()).with(authentication(asTraveler(traveler)))
                        .contentType(MediaType.APPLICATION_JSON).content(reschedule))
                .andExpect(status().isOk());

        mockMvc.perform(get("/bids/{id}", bid.getId()).with(authentication(as(sender, "ROLE_SENDER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestTripDelay").value(nullValue()));
        mockMvc.perform(get("/announcements/{id}", trip.getId()).with(authentication(asTraveler(traveler))))
                .andExpect(jsonPath("$.latestTripDelay").value(nullValue()));
        assertThat(delayRepository.findFirstByAnnouncementIdOrderByCreatedAtDesc(trip.getId())).isEmpty();
        Integer kept = jdbc.queryForObject(
                "SELECT COUNT(*) FROM trip_delays WHERE announcement_id = ? AND deleted_at IS NOT NULL",
                Integer.class, trip.getId());
        assertThat(kept).isEqualTo(1);
    }

    private boolean rawKeyPresent(MvcResult result, Object... path) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        for (int i = 0; i < path.length - 1; i++) {
            node = path[i] instanceof Integer idx ? node.get(idx) : node.get((String) path[i]);
        }
        return node.has((String) path[path.length - 1]);
    }
}
