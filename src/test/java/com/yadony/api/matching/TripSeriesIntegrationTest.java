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
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trajet récurrent par dates précises, de bout en bout : base réelle (V319), création par
 * le chemin ordinaire, série commune, refus par date et refus globaux.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TripSeriesIntegrationTest {

    private static final String OWNER = "uid-series-owner";
    private static final String OTHER = "uid-series-other";

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;

    private final LocalDate day = LocalDate.now().plusDays(10);

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

    private void seedUser(String uid) {
        var user = new UserEntity();
        user.setFirebaseUid(uid);
        user.setStatus(com.yadony.api.auth.UserStatus.ACTIVE);
        user.setKycStatus(com.yadony.api.auth.KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(List.of(com.yadony.api.auth.Role.TRAVELER)));
        userRepository.save(user);
    }

    private static String trip(String from, String to, LocalDate date) {
        return """
            {
              "departureCity": "%s",
              "arrivalCity": "%s",
              "departureDate": "%s",
              "departureTime": "10:00",
              "arrivalTime": "06:00",
              "arrivalDate": "%s",
              "availableKg": 12,
              "pricePerKg": 8,
              "transportMode": "PLANE",
              "pickupAddress": {"label": "Orly", "lat": 48.72, "lng": 2.37},
              "deliveryAddress": {"label": "Dakar centre", "lat": 14.69, "lng": -17.44},
              "handoverDeadline": "%sT07:30:00"
            }
            """.formatted(from, to, date, date.plusDays(1), date.minusDays(1));
    }

    private UUID createSource() throws Exception {
        String json = mockMvc.perform(post("/announcements").with(authentication(as(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(trip("Paris", "Dakar", day)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(json).get("id").asText());
    }

    private ResultActions series(String uid, UUID sourceId, boolean draft, LocalDate... dates) throws Exception {
        String list = String.join(",", java.util.Arrays.stream(dates).map(d -> "\"" + d + "\"").toList());
        return mockMvc.perform(post("/announcements/" + sourceId + "/series").with(authentication(as(uid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dates\": [" + list + "], \"saveAsDraft\": " + draft + "}"));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    void publishesOneTripPerDate_inTheSourceSeries_withShiftedDeadlineAndArrival() throws Exception {
        UUID source = createSource();

        JsonNode res = body(series(OWNER, source, false, day.plusDays(14), day.plusDays(7))
                .andExpect(status().isOk()));

        assertThat(res.get("seriesId").asText()).isEqualTo(source.toString());
        assertThat(res.get("failed")).isEmpty();
        assertThat(res.get("created")).hasSize(2);
        // Dates triées, même trajet, série commune.
        assertThat(res.get("created").get(0).get("departureDate").asText()).isEqualTo(day.plusDays(7).toString());
        assertThat(res.get("created").get(1).get("seriesId").asText()).isEqualTo(source.toString());

        AnnouncementEntity copy = announcementRepository.findById(
                UUID.fromString(res.get("created").get(0).get("id").asText())).orElseThrow();
        assertThat(copy.getStatus()).isEqualTo(AnnouncementStatus.ACTIVE);
        assertThat(copy.getDepartureCity()).isEqualTo("Paris");
        assertThat(copy.getPickupAddressLabel()).isEqualTo("Orly");
        assertThat(copy.getTotalKg()).isEqualByComparingTo("12");
        assertThat(copy.getHandoverDeadline()).isEqualTo(LocalDateTime.of(day.plusDays(6), java.time.LocalTime.of(7, 30)));
        assertThat(copy.getArrivalDate()).isEqualTo(day.plusDays(8));
        assertThat(copy.getTripGroupId()).isNull();

        assertThat(announcementRepository.findById(source).orElseThrow().getSeriesId()).isEqualTo(source);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'TRIP_SERIES_CREATED' AND entity_id = ?",
                Long.class, source)).isEqualTo(1L);
    }

    @Test
    void draftMode_createsDrafts() throws Exception {
        UUID source = createSource();

        JsonNode res = body(series(OWNER, source, true, day.plusDays(7)).andExpect(status().isOk()));

        assertThat(res.get("created").get(0).get("status").asText()).isEqualTo("DRAFT");
    }

    @Test
    void dateAlreadyInTheSeries_isReportedAndOthersGoThrough() throws Exception {
        UUID source = createSource();
        series(OWNER, source, false, day.plusDays(7)).andExpect(status().isOk());

        JsonNode res = body(series(OWNER, source, false, day.plusDays(7), day.plusDays(21))
                .andExpect(status().isOk()));

        assertThat(res.get("created")).hasSize(1);
        assertThat(res.get("failed")).hasSize(1);
        assertThat(res.get("failed").get(0).get("code").asText()).isEqualTo("series-date-taken");
        assertThat(res.get("failed").get(0).get("date").asText()).isEqualTo(day.plusDays(7).toString());
    }

    @Test
    void pastDate_isReportedPerDate() throws Exception {
        UUID source = createSource();

        JsonNode res = body(series(OWNER, source, false, LocalDate.now().minusDays(2), day.plusDays(7))
                .andExpect(status().isOk()));

        assertThat(res.get("created")).hasSize(1);
        assertThat(res.get("failed").get(0).get("code").asText()).isEqualTo("invalid-departure-date");
    }

    @Test
    void sourceDate_isRejected() throws Exception {
        UUID source = createSource();

        series(OWNER, source, false, day)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("series-invalid-dates"));
    }

    @Test
    void multiLegTrip_isRejected() throws Exception {
        String legs = "{\"legs\": [" + trip("Paris", "Abidjan", day) + ","
                + trip("Abidjan", "Douala", day.plusDays(4)) + "]}";
        String json = mockMvc.perform(post("/announcements/trips").with(authentication(as(OWNER)))
                        .contentType(MediaType.APPLICATION_JSON).content(legs))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID firstLeg = UUID.fromString(objectMapper.readTree(json).get("legs").get(0).get("id").asText());

        series(OWNER, firstLeg, false, day.plusDays(14))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("series-multi-leg-trip"));
    }

    @Test
    void someoneElsesTrip_isNotFound() throws Exception {
        UUID source = createSource();

        series(OTHER, source, false, day.plusDays(7))
                .andExpect(status().isNotFound());
        assertThat(announcementRepository.findById(source).orElseThrow().getSeriesId()).isNull();
    }

    @Test
    void noDates_orTooMany_areRejectedByValidation() throws Exception {
        UUID source = createSource();

        series(OWNER, source, false).andExpect(status().isUnprocessableEntity());
        LocalDate[] thirteen = new LocalDate[13];
        for (int i = 0; i < 13; i++) thirteen[i] = day.plusDays(i + 1L);
        series(OWNER, source, false, thirteen).andExpect(status().isUnprocessableEntity());
    }

    @Test
    void withoutAuthentication_isUnauthorized() throws Exception {
        mockMvc.perform(post("/announcements/" + UUID.randomUUID() + "/series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dates\": [\"" + day + "\"], \"saveAsDraft\": false}"))
                .andExpect(status().isUnauthorized());
    }
}
