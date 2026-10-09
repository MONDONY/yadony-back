package com.yadony.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Voyage à plusieurs étapes (FLUTTER-4D) de bout en bout : création transactionnelle,
 * rollback, recherche par étape, regroupement dans « Mes trajets », étapes sœurs et
 * annulation d'une étape.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TripGroupIntegrationTest {

    private static final String OWNER = "uid-trip-owner";
    private static final String OTHER = "uid-trip-other";

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    private static UsernamePasswordAuthenticationToken as(String uid) {
        return new UsernamePasswordAuthenticationToken(uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @BeforeEach
    void cleanDb() {
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        seedUser(OWNER);
        seedUser(OTHER);
    }

    private UserEntity seedUser(String uid) {
        var user = new UserEntity();
        user.setFirebaseUid(uid);
        user.setStatus(com.yadony.api.auth.UserStatus.ACTIVE);
        user.setKycStatus(com.yadony.api.auth.KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(List.of(com.yadony.api.auth.Role.TRAVELER)));
        return userRepository.save(user);
    }

    private static String leg(String from, String to, LocalDate date, Object pricePerKg, boolean draft) {
        return """
            {
              "departureCity": "%s",
              "arrivalCity": "%s",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 10,
              "pricePerKg": %s,
              "transportMode": "PLANE",
              "pickupAddress": {"label": "%s", "lat": 48.85, "lng": 2.35},
              "deliveryAddress": {"label": "%s", "lat": 5.35, "lng": -4.0},
              "handoverDeadline": "%sT07:30:00",
              "saveAsDraft": %s
            }
            """.formatted(from, to, date, pricePerKg, from, to, date, draft);
    }

    private static String trip(String... legs) {
        return "{\"legs\": [" + String.join(",", legs) + "]}";
    }

    private JsonNode createTrip(String body) throws Exception {
        String json = mockMvc.perform(post("/announcements/trips").with(authentication(as(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode abidjanThenDouala() throws Exception {
        LocalDate d = LocalDate.now().plusDays(10);
        return createTrip(trip(
                leg("Paris", "Abidjan", d, 8, false),
                leg("Abidjan", "Douala", d.plusDays(4), 6, false)));
    }

    @Test
    void createsAllLegsInOneGroup_inTravelOrder() throws Exception {
        JsonNode res = abidjanThenDouala();

        String group = res.get("tripGroupId").asText();
        assertThat(res.get("legs")).hasSize(2);
        assertThat(res.get("legs").get(0).get("departureCity").asText()).isEqualTo("Paris");
        assertThat(res.get("legs").get(0).get("tripLegIndex").asInt()).isEqualTo(1);
        assertThat(res.get("legs").get(1).get("arrivalCity").asText()).isEqualTo("Douala");
        assertThat(res.get("legs").get(1).get("tripLegIndex").asInt()).isEqualTo(2);
        // Chaque étape porte le nombre final d'étapes, y compris la première.
        assertThat(res.get("legs").get(0).get("tripLegCount").asInt()).isEqualTo(2);
        assertThat(res.get("legs").get(1).get("tripGroupId").asText()).isEqualTo(group);
        // Kilos et prix propres à chaque étape.
        assertThat(res.get("legs").get(0).get("pricePerKg").decimalValue()).isEqualByComparingTo("8");
        assertThat(res.get("legs").get(1).get("pricePerKg").decimalValue()).isEqualByComparingTo("6");

        List<AnnouncementEntity> stored =
                announcementRepository.findByTripGroupIdOrderByTripLegIndexAsc(UUID.fromString(group));
        assertThat(stored).extracting(AnnouncementEntity::getStatus)
                .containsExactly(AnnouncementStatus.ACTIVE, AnnouncementStatus.ACTIVE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE entity_type = 'TRIP_GROUP' AND action = 'TRIP_GROUP_CREATED'"
                        + " AND entity_id = ?", Long.class, UUID.fromString(group))).isEqualTo(1L);
    }

    // FLUTTER-GE : chaque étape en avion garde ses propres escales, sans reprendre la première.
    @Test
    void eachLegKeepsItsOwnStopsCount() throws Exception {
        LocalDate d = LocalDate.now().plusDays(10);
        JsonNode res = createTrip(trip(
                withStops(leg("Paris", "Abidjan", d, 8, false), 0),
                withStops(leg("Abidjan", "Douala", d.plusDays(4), 6, false), 2),
                leg("Douala", "Yaoundé", d.plusDays(6), 5, false)));

        assertThat(res.get("legs").get(0).get("stopsCount").asInt()).isZero();
        assertThat(res.get("legs").get(1).get("stopsCount").asInt()).isEqualTo(2);
        assertThat(res.get("legs").get(2).hasNonNull("stopsCount")).isFalse();
        List<AnnouncementEntity> stored = announcementRepository.findByTripGroupIdOrderByTripLegIndexAsc(
                UUID.fromString(res.get("tripGroupId").asText()));
        assertThat(stored).extracting(AnnouncementEntity::getStopsCount).containsExactly(0, 2, null);
    }

    private static String withStops(String leg, int stopsCount) {
        return leg.replace("\"transportMode\": \"PLANE\",",
                "\"transportMode\": \"PLANE\", \"stopsCount\": " + stopsCount + ",");
    }

    @Test
    void invalidSecondLeg_rollsBackTheWholeTrip() throws Exception {
        LocalDate d = LocalDate.now().plusDays(10);
        // Étape 2 en mode KG sans prix : refusée par la validation d'annonce, après que
        // l'étape 1 a déjà été enregistrée dans la transaction.
        mockMvc.perform(post("/announcements/trips").with(authentication(as(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trip(leg("Paris", "Abidjan", d, 8, false),
                                leg("Abidjan", "Douala", d.plusDays(4), "null", false))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("invalid-price"))
                .andExpect(jsonPath("$.legIndex").value(2));

        assertThat(announcementRepository.count()).isZero();
    }

    @Test
    void unchainedCities_areRefusedWithTheFaultyLeg() throws Exception {
        LocalDate d = LocalDate.now().plusDays(10);
        mockMvc.perform(post("/announcements/trips").with(authentication(as(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trip(leg("Paris", "Abidjan", d, 8, false),
                                leg("Dakar", "Douala", d.plusDays(4), 6, false))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("trip-leg-city-mismatch"))
                .andExpect(jsonPath("$.legIndex").value(2));
        assertThat(announcementRepository.count()).isZero();
    }

    @Test
    void legBeforePreviousArrival_isRefused() throws Exception {
        LocalDate d = LocalDate.now().plusDays(10);
        mockMvc.perform(post("/announcements/trips").with(authentication(as(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trip(leg("Paris", "Abidjan", d, 8, false),
                                leg("Abidjan", "Douala", d.minusDays(1), 6, false))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("trip-leg-date-before-previous"));
        assertThat(announcementRepository.count()).isZero();
    }

    @Test
    void singleLeg_isABadRequestForThisEndpoint() throws Exception {
        LocalDate d = LocalDate.now().plusDays(10);
        mockMvc.perform(post("/announcements/trips").with(authentication(as(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(trip(leg("Paris", "Abidjan", d, 8, false))))
                .andExpect(status().is4xxClientError());
        assertThat(announcementRepository.count()).isZero();
    }

    @Test
    void eachLegIsFoundAloneInSearch() throws Exception {
        abidjanThenDouala();

        mockMvc.perform(get("/announcements").with(authentication(as(OTHER)))
                        .param("departureCity", "Abidjan").param("arrivalCity", "Douala"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].departureCity").value("Abidjan"));
        mockMvc.perform(get("/announcements").with(authentication(as(OTHER)))
                        .param("departureCity", "Paris").param("arrivalCity", "Abidjan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void myTrips_carryTheGroupForVisualGrouping() throws Exception {
        String group = abidjanThenDouala().get("tripGroupId").asText();

        mockMvc.perform(get("/announcements/my").with(authentication(as(OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].tripGroupId").value(group))
                .andExpect(jsonPath("$.content[1].tripGroupId").value(group))
                .andExpect(jsonPath("$.content[0].tripLegCount").value(2));
    }

    @Test
    void tripLegs_listsSiblingsForAnyLeg_andIsEmptyForASingleTrip() throws Exception {
        JsonNode res = abidjanThenDouala();
        String leg2 = res.get("legs").get(1).get("id").asText();

        mockMvc.perform(get("/announcements/" + leg2 + "/trip-legs").with(authentication(as(OTHER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripGroupId").value(res.get("tripGroupId").asText()))
                .andExpect(jsonPath("$.legCount").value(2))
                .andExpect(jsonPath("$.legs[0].legIndex").value(1))
                .andExpect(jsonPath("$.legs[0].departureCity").value("Paris"))
                .andExpect(jsonPath("$.legs[1].id").value(leg2));

        String single = objectMapper.readTree(mockMvc.perform(post("/announcements")
                        .with(authentication(as(OWNER))).contentType(MediaType.APPLICATION_JSON)
                        .content(leg("Lyon", "Dakar", LocalDate.now().plusDays(20), 5, false)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get("/announcements/" + single + "/trip-legs").with(authentication(as(OTHER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripGroupId").doesNotExist())
                .andExpect(jsonPath("$.legCount").value(0))
                .andExpect(jsonPath("$.legs.length()").value(0));
    }

    @Test
    void draftTrip_isHiddenFromOthers_butListedForItsOwner() throws Exception {
        LocalDate d = LocalDate.now().plusDays(10);
        JsonNode res = createTrip(trip(
                leg("Paris", "Abidjan", d, 8, true),
                leg("Abidjan", "Douala", d.plusDays(4), 6, true)));
        assertThat(res.get("legs").get(0).get("status").asText()).isEqualTo("DRAFT");
        String leg1 = res.get("legs").get(0).get("id").asText();

        mockMvc.perform(get("/announcements/" + leg1 + "/trip-legs").with(authentication(as(OTHER))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/announcements/" + leg1 + "/trip-legs").with(authentication(as(OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs.length()").value(2));
    }

    @Test
    void publishingOneDraftLeg_keepsTheOtherDraftHiddenFromOthers() throws Exception {
        LocalDate d = LocalDate.now().plusDays(10);
        JsonNode res = createTrip(trip(
                leg("Paris", "Abidjan", d, 8, true),
                leg("Abidjan", "Douala", d.plusDays(4), 6, true)));
        String leg1 = res.get("legs").get(0).get("id").asText();
        mockMvc.perform(post("/announcements/" + leg1 + "/publish").with(authentication(as(OWNER))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/announcements/" + leg1 + "/trip-legs").with(authentication(as(OTHER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legCount").value(2))
                .andExpect(jsonPath("$.legs.length()").value(1))
                .andExpect(jsonPath("$.legs[0].id").value(leg1));
    }

    @Test
    void cancellingOneLeg_leavesTheOthersUntouched() throws Exception {
        JsonNode res = abidjanThenDouala();
        String leg1 = res.get("legs").get(0).get("id").asText();
        String leg2 = res.get("legs").get(1).get("id").asText();

        mockMvc.perform(delete("/announcements/" + leg1).with(authentication(as(OWNER))))
                .andExpect(status().isNoContent());

        AnnouncementEntity remaining = announcementRepository.findById(UUID.fromString(leg2)).orElseThrow();
        assertThat(remaining.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        assertThat(remaining.getTripGroupId()).isEqualTo(UUID.fromString(res.get("tripGroupId").asText()));
        mockMvc.perform(get("/announcements/" + leg2 + "/trip-legs").with(authentication(as(OWNER))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legs.length()").value(1));
    }

    @Test
    void unknownAnnouncement_tripLegsIs404() throws Exception {
        mockMvc.perform(get("/announcements/" + UUID.randomUUID() + "/trip-legs").with(authentication(as(OTHER))))
                .andExpect(status().isNotFound());
    }
}
