package com.yadony.api.matching;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TripRecurrenceControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TripRecurrenceRepository repository;
    @Autowired UserRepository userRepository;

    private static final String TRAVELER_UID = "firebase-recurrence-traveler";
    private static final String OTHER_UID    = "firebase-recurrence-other";

    private static UsernamePasswordAuthenticationToken asTraveler(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null, List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @BeforeEach
    void cleanDb() {
        repository.deleteAll();
        userRepository.deleteAll();
        seedTraveler(TRAVELER_UID, "+33600000031");
        seedTraveler(OTHER_UID, "+33600000032");
    }

    private void seedTraveler(String firebaseUid, String phone) {
        var user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(List.of(Role.TRAVELER)));
        userRepository.save(user);
    }

    // active=false → pas de génération réelle d'annonces pendant les tests CRUD.
    private String recurrenceJson(String weekdays, boolean active) throws Exception {
        return objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("departureCity", "Paris");
            put("arrivalCity", "Dakar");
            put("transportMode", "PLANE");
            put("capacityUnit", "SUITCASE_23KG");
            put("availableKg", 23.0);
            put("pricePerKg", 8.0);
            put("acceptedCategories", List.of("Vêtements", "Documents"));
            put("pickupAddress", Map.of("label", "12 rue de la Paix", "lat", 48.86, "lng", 2.33));
            put("deliveryAddress", Map.of("label", "Aéroport CDG", "lat", 49.01, "lng", 2.55));
            put("departureTime", "14:00");
            put("weekdays", weekdays);
            put("horizonDays", 14);
            put("active", active);
        }});
    }

    @Test
    void list_emptyDb_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/trip-recurrences")
                .with(authentication(asTraveler(TRAVELER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void create_thenList_returnsCreatedRecurrence() throws Exception {
        mockMvc.perform(post("/trip-recurrences")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(recurrenceJson("0000100", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.weekdays").value("0000100"))
                .andExpect(jsonPath("$.arrivalCity").value("Dakar"))
                .andExpect(jsonPath("$.pickupAddress.label").value("12 rue de la Paix"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.id").exists());

        mockMvc.perform(get("/trip-recurrences")
                .with(authentication(asTraveler(TRAVELER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void create_completeSchedule_roundTripsAllPublicationSettings() throws Exception {
        ObjectNode payload = (ObjectNode) objectMapper.readTree(recurrenceJson("1000100", false));
        payload.put("startDate", "2026-09-01");
        payload.put("endDate", "2027-03-31");
        payload.put("weekInterval", 2);
        payload.put("publicationLeadDays", 21);
        payload.put("handoverLeadDays", 2);
        payload.put("pricingMode", "MIXED");
        payload.put("negotiable", true);
        payload.put("currency", "USD");
        payload.put("description", "Remise possible la veille sur rendez-vous.");
        payload.set("refusedCategories", objectMapper.valueToTree(List.of("Produits frais / périssables")));

        mockMvc.perform(post("/trip-recurrences")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").value("2026-09-01"))
                .andExpect(jsonPath("$.endDate").value("2027-03-31"))
                .andExpect(jsonPath("$.weekInterval").value(2))
                .andExpect(jsonPath("$.publicationLeadDays").value(21))
                .andExpect(jsonPath("$.handoverLeadDays").value(2))
                .andExpect(jsonPath("$.pricingMode").value("MIXED"))
                .andExpect(jsonPath("$.negotiable").value(true))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.description").value("Remise possible la veille sur rendez-vous."))
                .andExpect(jsonPath("$.refusedCategories[0]").value("Produits frais / périssables"))
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void create_legacySchedule_returnsCompatibleDefaults() throws Exception {
        mockMvc.perform(post("/trip-recurrences")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(recurrenceJson("1000000", false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startDate").exists())
                .andExpect(jsonPath("$.endDate").doesNotExist())
                .andExpect(jsonPath("$.weekInterval").value(1))
                .andExpect(jsonPath("$.publicationLeadDays").value(14))
                .andExpect(jsonPath("$.handoverLeadDays").value(0))
                .andExpect(jsonPath("$.pricingMode").value("KG"))
                .andExpect(jsonPath("$.negotiable").value(false))
                .andExpect(jsonPath("$.currency").value("EUR"));
    }

    @Test
    void create_rejectsInvalidCompleteSchedule() throws Exception {
        ObjectNode invalidPeriod = (ObjectNode) objectMapper.readTree(recurrenceJson("1000000", false));
        invalidPeriod.put("startDate", "2026-10-01");
        invalidPeriod.put("endDate", "2026-09-30");

        ObjectNode invalidInterval = invalidPeriod.deepCopy();
        invalidInterval.remove("endDate");
        invalidInterval.put("weekInterval", 5);

        ObjectNode invalidPublicationLead = invalidPeriod.deepCopy();
        invalidPublicationLead.remove("endDate");
        invalidPublicationLead.put("publicationLeadDays", 10);

        for (ObjectNode payload : List.of(invalidPeriod, invalidInterval, invalidPublicationLead)) {
            mockMvc.perform(post("/trip-recurrences")
                    .with(authentication(asTraveler(TRAVELER_UID)))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload)))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void update_changesWeekdays() throws Exception {
        String created = mockMvc.perform(post("/trip-recurrences")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(recurrenceJson("0000100", false)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(put("/trip-recurrences/" + id)
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(recurrenceJson("1000001", false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weekdays").value("1000001"));
    }

    @Test
    void delete_removesRecurrence() throws Exception {
        String created = mockMvc.perform(post("/trip-recurrences")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(recurrenceJson("0000100", false)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(delete("/trip-recurrences/" + id)
                .with(authentication(asTraveler(TRAVELER_UID))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/trip-recurrences")
                .with(authentication(asTraveler(TRAVELER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void delete_otherUserRecurrence_returns404() throws Exception {
        String created = mockMvc.perform(post("/trip-recurrences")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(recurrenceJson("0000100", false)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(delete("/trip-recurrences/" + id)
                .with(authentication(asTraveler(OTHER_UID))))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_invalidWeekdays_returns422() throws Exception {
        mockMvc.perform(post("/trip-recurrences")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(recurrenceJson("abc", false)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/trip-recurrences"))
                .andExpect(status().isUnauthorized());
    }
}
