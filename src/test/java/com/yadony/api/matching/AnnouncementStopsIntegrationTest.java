package com.yadony.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Escales d'un trajet en avion (FLUTTER-GE) et filtre de recherche « Escales » (FLUTTER-GD).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementStopsIntegrationTest {

    private static final String UID = "uid-stops-traveler";

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void cleanDb() {
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        var cache = cacheManager.getCache("announcements-search");
        if (cache != null) {
            cache.clear();
        }
    }

    private static UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    private void seedTraveler() {
        var user = new com.yadony.api.auth.UserEntity();
        user.setFirebaseUid(UID);
        user.setStatus(com.yadony.api.auth.UserStatus.ACTIVE);
        user.setKycStatus(com.yadony.api.auth.KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(List.of(com.yadony.api.auth.Role.TRAVELER)));
        userRepository.save(user);
    }

    private String body(String mode, String stopsJson) {
        String date = LocalDate.now().plusDays(10).toString();
        String stops = stopsJson == null ? "" : ",\n  \"stopsCount\": " + stopsJson;
        return """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 10,
              "pricePerKg": 5,
              "transportMode": "%s",
              "pickupAddress": {"label": "CDG", "lat": 49.009, "lng": 2.547},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447},
              "handoverDeadline": "%sT07:30:00"%s
            }
            """.formatted(date, mode, date, stops);
    }

    private String create(String mode, String stopsJson) throws Exception {
        var res = mockMvc.perform(post("/announcements").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(mode, stopsJson)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(res.getResponse().getContentAsString());
        return node.get("id").asText();
    }

    @Test
    void create_plane_withStops_isPersistedAndExposed() throws Exception {
        seedTraveler();
        mockMvc.perform(post("/announcements").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("PLANE", "1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stopsCount").value(1));
    }

    @Test
    void create_withoutStops_staysNull() throws Exception {
        seedTraveler();
        String id = create("PLANE", null);
        mockMvc.perform(get("/announcements/" + id).with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopsCount").doesNotExist());
    }

    @Test
    void create_car_withStops_dropsTheValue() throws Exception {
        seedTraveler();
        mockMvc.perform(post("/announcements").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("CAR", "1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stopsCount").doesNotExist());
    }

    @Test
    void create_withOutOfRangeStops_returns422ProblemDetail() throws Exception {
        seedTraveler();
        mockMvc.perform(post("/announcements").with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("PLANE", "3")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.containsString("invalid-stops-count")));
    }

    @Test
    void update_withoutStops_keepsValue_andWithStopsReplacesIt() throws Exception {
        seedTraveler();
        String id = create("PLANE", "2");

        mockMvc.perform(put("/announcements/" + id).with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("PLANE", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopsCount").value(2));

        mockMvc.perform(put("/announcements/" + id).with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("PLANE", "0")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopsCount").value(0));

        mockMvc.perform(put("/announcements/" + id).with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("BUS", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stopsCount").doesNotExist());
    }

    @Test
    void search_maxStopsZero_keepsOnlyDeclaredDirect() throws Exception {
        UUID direct = seed(0).getId();
        seed(1);
        seed(2);
        seed(null);

        mockMvc.perform(get("/announcements").param("maxStops", "0").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(direct.toString()))
                .andExpect(jsonPath("$.content[0].stopsCount").value(0));
    }

    @Test
    void search_maxStopsOne_keepsDirectOneStopAndUnknown() throws Exception {
        UUID direct = seed(0).getId();
        UUID oneStop = seed(1).getId();
        seed(2);
        UUID unknown = seed(null).getId();

        mockMvc.perform(get("/announcements").param("maxStops", "1").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(
                        direct.toString(), oneStop.toString(), unknown.toString())));
    }

    @Test
    void search_withoutMaxStops_orAnyValue_returnsEverything() throws Exception {
        seed(0);
        seed(1);
        seed(2);
        seed(null);

        mockMvc.perform(get("/announcements").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));
        mockMvc.perform(get("/announcements").param("maxStops", "2").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void search_maxStops_isPaginatedInQuery() throws Exception {
        for (int i = 0; i < 3; i++) {
            seed(0);
            seed(2);
        }
        mockMvc.perform(get("/announcements").param("maxStops", "0").param("size", "2")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    private AnnouncementEntity seed(Integer stops) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(UUID.randomUUID());
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDepartureDate(LocalDate.now().plusDays(7));
        e.setAvailableKg(new BigDecimal("8"));
        e.setTotalKg(new BigDecimal("8"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setStatus(AnnouncementStatus.ACTIVE);
        e.setTransportMode(TransportMode.PLANE);
        e.setStopsCount(stops);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(48.8566));
        e.setPickupLng(BigDecimal.valueOf(2.3522));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e);
    }
}
