package com.yadony.api.triptemplate;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
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
class TripTemplateControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TripTemplateRepository repository;
    @Autowired UserRepository userRepository;

    private static final String TRAVELER_UID = "firebase-trip-traveler";
    private static final String OTHER_UID    = "firebase-trip-other";

    private static UsernamePasswordAuthenticationToken asTraveler(String firebaseUid) {
        return new UsernamePasswordAuthenticationToken(
                firebaseUid, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @BeforeEach
    void cleanDb() {
        repository.deleteAll();
        userRepository.deleteAll();
        seedTraveler(TRAVELER_UID, "+33600000021");
        seedTraveler(OTHER_UID, "+33600000022");
    }

    private void seedTraveler(String firebaseUid, String phone) {
        var user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(List.of(Role.TRAVELER)));
        userRepository.save(user);
    }

    private String templateJson(String label) throws Exception {
        return objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("label", label);
            put("emoji", "🇸🇳");
            put("departureCity", "Paris");
            put("departureLat", 48.85);
            put("departureLng", 2.35);
            put("arrivalCity", "Dakar");
            put("arrivalLat", 14.71);
            put("arrivalLng", -17.46);
            put("transportMode", "PLANE");
            put("capacityUnit", "SUITCASE_23KG");
            put("availableKg", 23);
            put("pricePerKg", 8.0);
            put("acceptedCategories", List.of("Vêtements", "Documents"));
        }});
    }

    @Test
    void list_emptyDb_returnsEmptyArray() throws Exception {
        mockMvc.perform(get("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void create_thenList_returnsCreatedTemplate() throws Exception {
        mockMvc.perform(post("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(templateJson("Mon Paris-Dakar")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.label").value("Mon Paris-Dakar"))
                .andExpect(jsonPath("$.arrivalCity").value("Dakar"))
                .andExpect(jsonPath("$.pricePerKg").value(8.0))
                .andExpect(jsonPath("$.acceptedCategories.length()").value(2))
                .andExpect(jsonPath("$.id").exists());

        mockMvc.perform(get("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].label").value("Mon Paris-Dakar"));
    }

    @Test
    void update_changesLabel() throws Exception {
        String created = mockMvc.perform(post("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(templateJson("Original")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        String updated = objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("label", "Modifié");
            put("departureCity", "Lyon");
            put("arrivalCity", "Abidjan");
            put("transportMode", "PLANE");
            put("capacityUnit", "KG_FREE");
            put("availableKg", 30);
            put("pricePerKg", 9.0);
            put("acceptedCategories", List.of("Documents"));
        }});

        mockMvc.perform(put("/trip-templates/" + id)
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updated))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.label").value("Modifié"))
                .andExpect(jsonPath("$.departureCity").value("Lyon"));
    }

    @Test
    void delete_removesTemplate() throws Exception {
        String created = mockMvc.perform(post("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(templateJson("A supprimer")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(delete("/trip-templates/" + id)
                .with(authentication(asTraveler(TRAVELER_UID))))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void update_otherUserTemplate_returns404() throws Exception {
        String created = mockMvc.perform(post("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(templateJson("Privé")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(delete("/trip-templates/" + id)
                .with(authentication(asTraveler(OTHER_UID))))
                .andExpect(status().isNotFound());
    }

    @Test
    void create_invalidPayload_returns422() throws Exception {
        String bad = objectMapper.writeValueAsString(Map.of(
                "label", "",
                "departureCity", "Paris",
                "arrivalCity", "Dakar",
                "transportMode", "PLANE",
                "capacityUnit", "SUITCASE_23KG",
                "availableKg", 23,
                "pricePerKg", 8.0));
        mockMvc.perform(post("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bad))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/trip-templates"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void create_fullForm_roundTripsEveryField() throws Exception {
        String body = objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("label", "Abidjan-Paris complet");
            put("departureCity", "Abidjan");
            put("arrivalCity", "Paris");
            put("transportMode", "PLANE");
            put("capacityUnit", "SUITCASE_23KG");
            put("availableKg", 23);
            put("pricePerKg", 2000.0);
            put("acceptedCategories", List.of("Vêtements & tissus"));
            put("arrivalTime", "06:30");
            put("currency", "XOF");
            put("pricingMode", "KG");
            put("acceptedPaymentMethods", List.of("CASH", "MOBILE_MONEY"));
            put("negotiable", true);
            put("refusedTypes", List.of("Téléphone & électronique"));
            put("description", "Pas de liquide");
            put("pickupAddress", java.util.Map.of("label", "Cocody", "lat", 5.35, "lng", -3.99));
            put("deliveryAddress", java.util.Map.of("label", "Gare de Lyon", "lat", 48.84, "lng", 2.37));
            put("departureTime", "22:00");
            put("handoverLeadDays", 2);
            put("departureCountryCode", "CI");
            put("arrivalCountryCode", "FR");
        }});

        mockMvc.perform(post("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.pricingMode").value("KG"))
                .andExpect(jsonPath("$.acceptedPaymentMethods.length()").value(2))
                .andExpect(jsonPath("$.cashAccepted").value(true))
                .andExpect(jsonPath("$.negotiable").value(true))
                .andExpect(jsonPath("$.refusedTypes[0]").value("Téléphone & électronique"))
                .andExpect(jsonPath("$.description").value("Pas de liquide"))
                .andExpect(jsonPath("$.pickupAddress.label").value("Cocody"))
                .andExpect(jsonPath("$.deliveryAddress.lat").value(48.84))
                .andExpect(jsonPath("$.departureTime").value("22:00"))
                .andExpect(jsonPath("$.arrivalTime").value("06:30"))
                .andExpect(jsonPath("$.handoverLeadDays").value(2))
                .andExpect(jsonPath("$.departureCountryCode").value("CI"))
                .andExpect(jsonPath("$.arrivalCountryCode").value("FR"));

        mockMvc.perform(get("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pickupAddress.lng").value(-3.99))
                .andExpect(jsonPath("$[0].acceptedPaymentMethods").isArray());
    }

    @Test
    void create_legacyBody_isAcceptedWithDefaults() throws Exception {
        String legacy = objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("label", "Ancien client");
            put("departureCity", "Paris");
            put("arrivalCity", "Dakar");
            put("transportMode", "PLANE");
            put("capacityUnit", "SUITCASE_23KG");
            put("availableKg", 23);
            put("pricePerKg", 8.0);
            put("cashAccepted", true);
        }});

        mockMvc.perform(post("/trip-templates")
                .with(authentication(asTraveler(TRAVELER_UID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(legacy))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cashAccepted").value(true))
                .andExpect(jsonPath("$.acceptedPaymentMethods.length()").value(2))
                .andExpect(jsonPath("$.pricingMode").value("KG"))
                .andExpect(jsonPath("$.negotiable").value(false))
                .andExpect(jsonPath("$.currency").doesNotExist())
                .andExpect(jsonPath("$.pickupAddress").doesNotExist());
    }
}
