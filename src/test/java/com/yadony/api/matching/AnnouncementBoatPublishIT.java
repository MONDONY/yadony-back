package com.yadony.api.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.config.PlatformSettingKey;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.triptemplate.TripTemplateRepository;
import org.junit.jupiter.api.AfterEach;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Le bateau est réservé aux transporteurs vérifiés (badge GP Pro ou compte PRO), en plus de
 * l'interrupteur du mode : création, brouillon, publication, modification, modèles et récurrences.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AnnouncementBoatPublishIT {

    private static final String UID = "uid-boat-carrier";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired TripTemplateRepository templateRepository;
    @Autowired TripRecurrenceRepository recurrenceRepository;
    @Autowired PlatformSettingsService platformSettings;

    private static UsernamePasswordAuthenticationToken asTraveler() {
        return new UsernamePasswordAuthenticationToken(UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @BeforeEach
    void setUp() {
        recurrenceRepository.deleteAll();
        templateRepository.deleteAll();
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "true"), UUID.randomUUID());
    }

    @AfterEach
    void closeBoat() {
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "false"), UUID.randomUUID());
    }

    private UserEntity seed(boolean gpPro, boolean pro) {
        var user = new UserEntity();
        user.setFirebaseUid(UID);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(List.of(Role.TRAVELER)));
        user.setGpPro(gpPro);
        user.setProAccount(pro);
        return userRepository.save(user);
    }

    private void setBadges(boolean gpPro, boolean pro) {
        UserEntity u = userRepository.findByFirebaseUid(UID).orElseThrow();
        u.setGpPro(gpPro);
        u.setProAccount(pro);
        userRepository.save(u);
    }

    private String tripBody(String mode, boolean draft) {
        String date = LocalDate.now().plusDays(10).toString();
        return """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 10,
              "pricePerKg": 5,
              "transportMode": "%s",
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447},
              "handoverDeadline": "%sT07:30:00"%s
            }
            """.formatted(date, mode, date, draft ? ",\n  \"saveAsDraft\": true" : "");
    }

    private String createTrip(String mode, boolean draft) throws Exception {
        String res = mockMvc.perform(post("/announcements")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tripBody(mode, draft)))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res).get("id").asText();
    }

    @Test
    void createBoatTrip_kycOnly_403() throws Exception {
        seed(false, false);

        mockMvc.perform(post("/announcements")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tripBody("BOAT", false)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("boat-carrier-not-verified"))
            .andExpect(jsonPath("$.type").value(endsWith("boat-carrier-not-verified")));
    }

    @Test
    void createBoatDraft_kycOnly_403() throws Exception {
        seed(false, false);

        mockMvc.perform(post("/announcements")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tripBody("BOAT", true)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("boat-carrier-not-verified"));
    }

    @Test
    void createBoatTrip_gpPro_201() throws Exception {
        seed(true, false);

        mockMvc.perform(post("/announcements")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tripBody("BOAT", false)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.transportMode").value("BOAT"));
    }

    @Test
    void createBoatTrip_proAccount_201() throws Exception {
        seed(false, true);

        mockMvc.perform(post("/announcements")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tripBody("BOAT", false)))
            .andExpect(status().isCreated());
    }

    @Test
    void createPlaneTrip_kycOnly_stillAccepted() throws Exception {
        seed(false, false);

        createTrip("PLANE", false);
    }

    @Test
    void boatDisabled_returns422BeforeVerification() throws Exception {
        seed(false, false);
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "false"), UUID.randomUUID());

        mockMvc.perform(post("/announcements")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tripBody("BOAT", false)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("transport-mode-disabled"));
    }

    @Test
    void editPlaneTripToBoat_kycOnly_403() throws Exception {
        seed(false, false);
        String id = createTrip("PLANE", false);

        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tripBody("BOAT", false)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("boat-carrier-not-verified"));
    }

    @Test
    void editPlaneTripToBoat_gpPro_200() throws Exception {
        seed(true, false);
        String id = createTrip("PLANE", false);

        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tripBody("BOAT", false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transportMode").value("BOAT"));
    }

    @Test
    void editExistingBoatTrip_afterBadgeRemoved_200() throws Exception {
        seed(true, false);
        String id = createTrip("BOAT", false);
        setBadges(false, false);

        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(tripBody("BOAT", false)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transportMode").value("BOAT"));
    }

    @Test
    void publishBoatDraft_afterBadgeRemoved_403() throws Exception {
        seed(true, false);
        String id = createTrip("BOAT", true);
        setBadges(false, false);

        mockMvc.perform(post("/announcements/" + id + "/publish")
                .with(authentication(asTraveler())))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("boat-carrier-not-verified"));
    }

    @Test
    void publishBoatDraft_gpPro_200() throws Exception {
        seed(true, false);
        String id = createTrip("BOAT", true);

        mockMvc.perform(post("/announcements/" + id + "/publish")
                .with(authentication(asTraveler())))
            .andExpect(status().isOk());
    }

    @Test
    void boatTemplate_422() throws Exception {
        seed(true, false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("label", "Traversée");
        body.put("departureCity", "Marseille");
        body.put("arrivalCity", "Dakar");
        body.put("transportMode", "BOAT");
        body.put("capacityUnit", "KG_FREE");
        body.put("availableKg", 500);
        body.put("pricePerKg", 5.0);

        mockMvc.perform(post("/trip-templates")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("boat-template-unsupported"))
            .andExpect(jsonPath("$.type").value(endsWith("boat-template-unsupported")));
    }

    @Test
    void boatRecurrence_422() throws Exception {
        seed(true, false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("departureCity", "Marseille");
        body.put("arrivalCity", "Dakar");
        body.put("transportMode", "BOAT");
        body.put("capacityUnit", "KG_FREE");
        body.put("availableKg", 500.0);
        body.put("pricePerKg", 5.0);
        body.put("pickupAddress", Map.of("label", "Port", "lat", 43.3, "lng", 5.37));
        body.put("deliveryAddress", Map.of("label", "Dakar", "lat", 14.69, "lng", -17.44));
        body.put("departureTime", "14:00");
        body.put("weekdays", "0000100");
        body.put("horizonDays", 14);
        body.put("active", false);

        mockMvc.perform(post("/trip-recurrences")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("boat-template-unsupported"));
    }
}
