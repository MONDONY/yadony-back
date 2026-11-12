package com.yadony.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
    @Autowired BidRepository bidRepository;

    private static UsernamePasswordAuthenticationToken asTraveler() {
        return new UsernamePasswordAuthenticationToken(UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @BeforeEach
    void setUp() {
        bidRepository.deleteAll();
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

    /** Trajet au kilo ; en bateau, avec sa fourchette d'arrivée estimée (obligatoire depuis le lot 3). */
    private String tripBody(String mode, boolean draft) {
        LocalDate departure = LocalDate.now().plusDays(10);
        String date = departure.toString();
        String range = "BOAT".equals(mode)
                ? ",\n  \"arrivalDate\": \"%s\",\n  \"arrivalDateMax\": \"%s\"".formatted(
                        departure.plusDays(20), departure.plusDays(30))
                : "";
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
              "handoverDeadline": "%sT07:30:00"%s%s
            }
            """.formatted(date, mode, date, range, draft ? ",\n  \"saveAsDraft\": true" : "");
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

    // ─── Lot 3, BA3 : trajet au m³, fourchette d'arrivée ─────────────────────────

    private static final String SENDER_UID = "uid-boat-sender";
    private static final LocalDate DEPARTURE = LocalDate.now().plusDays(10);

    private static UsernamePasswordAuthenticationToken asSender() {
        return new UsernamePasswordAuthenticationToken(SENDER_UID, null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    /** Trajet bateau au m³ : fourchette J+30..J+40 après un départ à J+10, 2,5 m³ à 600 €/m³, sans kilos. */
    private Map<String, Object> boatM3Body() {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("departureCity", "Marseille");
        b.put("arrivalCity", "Dakar");
        b.put("departureDate", DEPARTURE.toString());
        b.put("departureTime", "10:00");
        b.put("arrivalDate", DEPARTURE.plusDays(20).toString());
        b.put("arrivalDateMax", DEPARTURE.plusDays(30).toString());
        b.put("transportMode", "BOAT");
        b.put("volumeCapacityM3", 2.5);
        b.put("pricePerM3", 600);
        b.put("pickupAddress", Map.of("label", "Port de Marseille", "lat", 43.3, "lng", 5.37));
        b.put("deliveryAddress", Map.of("label", "Port de Dakar", "lat", 14.69, "lng", -17.44));
        b.put("handoverDeadline", DEPARTURE + "T07:30:00");
        return b;
    }

    private org.springframework.test.web.servlet.ResultActions postTrip(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/announcements")
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions putTrip(String id, Object body) throws Exception {
        return mockMvc.perform(put("/announcements/" + id)
                .with(authentication(asTraveler()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String str ? str : objectMapper.writeValueAsString(body)));
    }

    private String createdId(Map<String, Object> body) throws Exception {
        String res = postTrip(body).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(res).get("id").asText();
    }

    private void assertProblem(org.springframework.test.web.servlet.ResultActions actions, String code) throws Exception {
        actions.andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.type").value(endsWith(code)));
    }

    @Test
    void createBoatPerM3_201_withoutAnyKg() throws Exception {
        seed(true, false);

        String res = postTrip(boatM3Body()).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        JsonNode trip = objectMapper.readTree(res);
        assertThat(trip.path("pricePerM3").decimalValue()).isEqualByComparingTo("600");
        assertThat(trip.path("pricePerM3Display").decimalValue()).isGreaterThanOrEqualTo(new BigDecimal("600"));
        assertThat(trip.path("volumeCapacityM3").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(trip.path("availableVolumeM3").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(trip.path("arrivalDateMax").asText()).isEqualTo(DEPARTURE.plusDays(30).toString());
        // Aucun kilo factice : les clés de kilos disparaissent (sérialisation NON_NULL).
        assertThat(trip.has("availableKg")).isFalse();
        assertThat(trip.has("pricePerKg")).isFalse();

        AnnouncementEntity saved = announcementRepository.findById(UUID.fromString(trip.get("id").asText())).orElseThrow();
        assertThat(saved.isPricedPerM3()).isTrue();
        assertThat(saved.getAvailableKg()).isNull();
        assertThat(saved.getTotalKg()).isNull();
        assertThat(saved.getPricePerKg()).isNull();
        assertThat(saved.getPricePerKgEur()).isNull();
    }

    @Test
    void boatWithoutArrivalMax_422() throws Exception {
        seed(true, false);
        Map<String, Object> body = boatM3Body();
        body.remove("arrivalDateMax");

        assertProblem(postTrip(body), "arrival-range-invalid");
    }

    @Test
    void boatWithoutArrivalDate_422() throws Exception {
        seed(true, false);
        Map<String, Object> body = boatM3Body();
        body.remove("arrivalDate");

        assertProblem(postTrip(body), "arrival-range-invalid");
    }

    @Test
    void boatArrivalMaxBeforeMin_422() throws Exception {
        seed(true, false);
        Map<String, Object> body = boatM3Body();
        body.put("arrivalDateMax", DEPARTURE.plusDays(19).toString());

        assertProblem(postTrip(body), "arrival-range-invalid");
    }

    @Test
    void boatArrivalMaxBeyond120Days_422() throws Exception {
        seed(true, false);
        Map<String, Object> body = boatM3Body();
        body.put("arrivalDateMax", DEPARTURE.plusDays(121).toString());

        assertProblem(postTrip(body), "arrival-too-far");
    }

    @Test
    void boatPerM3WithoutVolume_422() throws Exception {
        seed(true, false);
        Map<String, Object> body = boatM3Body();
        body.remove("volumeCapacityM3");

        assertProblem(postTrip(body), "boat-volume-required");
    }

    @Test
    void boatBothPrices_422() throws Exception {
        seed(true, false);
        Map<String, Object> body = boatM3Body();
        body.put("availableKg", 100);
        body.put("pricePerKg", 8);

        assertProblem(postTrip(body), "boat-price-required");
    }

    @Test
    void boatWithoutAnyPrice_422() throws Exception {
        seed(true, false);
        Map<String, Object> body = boatM3Body();
        body.remove("pricePerM3");
        body.put("availableKg", 100);

        assertProblem(postTrip(body), "boat-price-required");
    }

    @Test
    void boatPriceGrid_422() throws Exception {
        seed(true, false);
        Map<String, Object> body = boatM3Body();
        body.remove("pricePerM3");
        body.put("availableKg", 100);
        body.put("pricePerKg", 0);
        body.put("pricingMode", "MIXED");

        assertProblem(postTrip(body), "boat-price-required");
    }

    @Test
    void boatPricePerM3_noUpperBound_201() throws Exception {
        seed(true, false);
        Map<String, Object> body = boatM3Body();
        body.put("pricePerM3", 25000);

        String id = createdId(body);

        assertThat(announcementRepository.findById(UUID.fromString(id)).orElseThrow().getPricePerM3())
                .isEqualByComparingTo("25000");
    }

    @Test
    void boatVolumeOutOfBounds_422Validation() throws Exception {
        seed(true, false);
        Map<String, Object> tooSmall = boatM3Body();
        tooSmall.put("volumeCapacityM3", 0.05);
        Map<String, Object> tooLarge = boatM3Body();
        tooLarge.put("volumeCapacityM3", 10000);
        Map<String, Object> freePrice = boatM3Body();
        freePrice.put("pricePerM3", 0);

        postTrip(tooSmall).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.volumeCapacityM3").exists());
        postTrip(tooLarge).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.volumeCapacityM3").exists());
        postTrip(freePrice).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.pricePerM3").exists());
    }

    @Test
    void planeWithoutAvailableKg_stillRefusedOnTheSameField() throws Exception {
        seed(false, false);
        Map<String, Object> body = boatM3Body();
        body.put("transportMode", "PLANE");
        body.remove("arrivalDate");
        body.remove("arrivalDateMax");
        body.put("pricePerKg", 5);

        postTrip(body).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.availableKg").exists());
    }

    @Test
    void boatPerKgWithOptionalVolume_201() throws Exception {
        seed(true, false);
        Map<String, Object> body = boatM3Body();
        body.remove("pricePerM3");
        body.put("availableKg", 500);
        body.put("pricePerKg", 4);
        body.put("volumeCapacityM3", 3);

        String id = createdId(body);

        AnnouncementEntity saved = announcementRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(saved.isPricedPerM3()).isFalse();
        assertThat(saved.getAvailableKg()).isEqualByComparingTo("500");
        assertThat(saved.getPricePerKg()).isEqualByComparingTo("4");
        assertThat(saved.getVolumeCapacityM3()).isEqualByComparingTo("3");
        assertThat(saved.getAvailableVolumeM3()).isEqualByComparingTo("3");
        assertThat(saved.getArrivalDateMax()).isEqualTo(DEPARTURE.plusDays(30));
    }

    @Test
    void planeIgnoresBoatFields() throws Exception {
        seed(false, false);
        Map<String, Object> body = boatM3Body();
        body.put("transportMode", "PLANE");
        body.put("availableKg", 20);
        body.put("pricePerKg", 9);
        body.put("volumeCapacityM3", 2);
        body.remove("arrivalDate");

        String id = createdId(body);

        AnnouncementEntity saved = announcementRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(saved.getAvailableVolumeM3()).isNull();
        assertThat(saved.getVolumeCapacityM3()).isNull();
        assertThat(saved.getPricePerM3()).isNull();
        assertThat(saved.getArrivalDateMax()).isNull();
        assertThat(saved.getPricePerKg()).isEqualByComparingTo("9");
    }

    @Test
    void response_alwaysSerializesBoatFields() throws Exception {
        seed(false, false);
        String id = createTrip("PLANE", false);

        JsonNode detail = objectMapper.readTree(mockMvc.perform(get("/announcements/" + id)
                        .with(authentication(asTraveler())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode mine = objectMapper.readTree(mockMvc.perform(get("/announcements/my")
                        .with(authentication(asTraveler())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("content").path(0);

        for (JsonNode node : List.of(detail, mine)) {
            for (String field : List.of("arrivalDateMax", "volumeCapacityM3", "availableVolumeM3",
                    "pricePerM3", "pricePerM3Display")) {
                assertThat(node.has(field)).as("%s toujours présent", field).isTrue();
                assertThat(node.get(field).isNull()).as("%s nul hors bateau", field).isTrue();
            }
        }
    }

    @Test
    void searchAndDetail_boatPerM3Trip_servedWithoutKg() throws Exception {
        seed(true, false);
        String id = createdId(boatM3Body());
        userRepository.save(sender());

        JsonNode page = objectMapper.readTree(mockMvc.perform(get("/announcements")
                        .param("departureCity", "Marseille").param("arrivalCity", "Dakar")
                        .param("minAvailableKg", "5")
                        .with(authentication(asSender())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("content").isArray()).isTrue();
        JsonNode all = objectMapper.readTree(mockMvc.perform(get("/announcements")
                        .param("departureCity", "Marseille").param("arrivalCity", "Dakar")
                        .with(authentication(asSender())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode card = null;
        for (JsonNode n : all.path("content")) {
            if (id.equals(n.path("id").asText())) card = n;
        }
        assertThat(card).as("le trajet au m³ ressort en recherche").isNotNull();
        assertThat(card.path("pricePerM3").decimalValue()).isEqualByComparingTo("600");
        assertThat(card.path("availableVolumeM3").decimalValue()).isEqualByComparingTo("2.5");
        assertThat(card.has("availableKg")).isFalse();

        mockMvc.perform(get("/announcements/" + id).with(authentication(asSender())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerM3").value(600.0))
                .andExpect(jsonPath("$.volumeCapacityM3").value(2.5));
    }

    @Test
    void editBoatPerKgToPerM3_withoutBids_200() throws Exception {
        seed(true, false);
        String id = createTrip("BOAT", false);

        putTrip(id, boatM3Body()).andExpect(status().isOk())
                .andExpect(jsonPath("$.pricePerM3").value(600.0))
                .andExpect(jsonPath("$.availableVolumeM3").value(2.5));

        AnnouncementEntity saved = announcementRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(saved.isPricedPerM3()).isTrue();
        assertThat(saved.getAvailableKg()).isNull();
        assertThat(saved.getTotalKg()).isNull();
        assertThat(saved.getPricePerKg()).isNull();
    }

    @Test
    void editBoatPricingUnit_withPendingBid_409() throws Exception {
        seed(true, false);
        String id = createTrip("BOAT", false);
        UserEntity sender = userRepository.save(sender());
        BidEntity bid = new BidEntity();
        bid.setAnnouncementId(UUID.fromString(id));
        bid.setSenderId(sender.getId());
        bid.setWeightKg(new BigDecimal("5"));
        bid.setStatus(BidStatus.PENDING);
        bid.setCurrency("EUR");
        bidRepository.save(bid);

        putTrip(id, boatM3Body()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("pricing-unit-locked"));
    }

    @Test
    void editBoatPerM3ToPlane_clearsBoatFields() throws Exception {
        seed(true, false);
        String id = createdId(boatM3Body());

        putTrip(id, tripBody("PLANE", false)).andExpect(status().isOk());

        AnnouncementEntity saved = announcementRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(saved.getTransportMode()).isEqualTo(TransportMode.PLANE);
        assertThat(saved.getArrivalDateMax()).isNull();
        assertThat(saved.getVolumeCapacityM3()).isNull();
        assertThat(saved.getAvailableVolumeM3()).isNull();
        assertThat(saved.getPricePerM3()).isNull();
        assertThat(saved.getAvailableKg()).isEqualByComparingTo("10");
        assertThat(saved.getPricePerKg()).isEqualByComparingTo("5");
    }

    @Test
    void editBoatTripWithRange_byOldClientWithoutRange_422() throws Exception {
        seed(true, false);
        String id = createTrip("BOAT", false);

        putTrip(id, legacyBoatBody()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("arrival-range-invalid"));
    }

    @Test
    void editLegacyBoatTrip_withoutBoatFields_staysPerKgWithoutRange() throws Exception {
        UserEntity traveler = seed(true, false);
        AnnouncementEntity legacy = new AnnouncementEntity();
        legacy.setTravelerId(traveler.getId());
        legacy.setDepartureCity("Paris");
        legacy.setArrivalCity("Dakar");
        legacy.setDepartureDate(DEPARTURE);
        legacy.setDepartureTime(LocalTime.of(10, 0));
        legacy.setTransportMode(TransportMode.BOAT);
        legacy.setPickupAddressLabel("Lyon");
        legacy.setPickupLat(new BigDecimal("45.748"));
        legacy.setPickupLng(new BigDecimal("4.846"));
        legacy.setDeliveryAddressLabel("Dakar");
        legacy.setDeliveryLat(new BigDecimal("14.693"));
        legacy.setDeliveryLng(new BigDecimal("-17.447"));
        legacy.setAvailableKg(new BigDecimal("10"));
        legacy.setTotalKg(new BigDecimal("10"));
        legacy.setPricePerKg(new BigDecimal("5"));
        legacy.setCurrency("EUR");
        legacy.setTimezone("Europe/Paris");
        legacy.setStatus(AnnouncementStatus.ACTIVE);
        legacy.setCapacityUnit(CapacityUnit.KG_EXACT);
        legacy.setHandoverDeadline(LocalDateTime.of(DEPARTURE, LocalTime.of(7, 30)));
        legacy.setAcceptedPaymentMethods(EnumSet.of(com.yadony.api.payments.cash.PaymentMethod.CASH));
        String id = announcementRepository.save(legacy).getId().toString();

        putTrip(id, legacyBoatBody()).andExpect(status().isOk())
                .andExpect(jsonPath("$.transportMode").value("BOAT"));

        AnnouncementEntity saved = announcementRepository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(saved.getArrivalDateMax()).isNull();
        assertThat(saved.getPricePerKg()).isEqualByComparingTo("5");
    }

    /** Corps d'une ancienne app : trajet bateau au kilo sans aucun champ bateau (ni fourchette). */
    private String legacyBoatBody() {
        String date = DEPARTURE.toString();
        return """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 10,
              "pricePerKg": 5,
              "transportMode": "BOAT",
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447},
              "handoverDeadline": "%sT07:30:00"
            }
            """.formatted(date, date);
    }

    private static UserEntity sender() {
        UserEntity sender = new UserEntity();
        sender.setFirebaseUid(SENDER_UID);
        sender.setStatus(UserStatus.ACTIVE);
        sender.setKycStatus(KycStatus.VERIFIED);
        sender.setRoles(new java.util.HashSet<>(List.of(Role.SENDER)));
        return sender;
    }

    // Les gardes de réservation (offre, paiement carte, devis, proposition de prix) verrouillent le
    // trajet par « FOR NO KEY UPDATE », que H2 ne connaît pas : prouvées sur PostgreSQL dans
    // BoatTripPostgresIT.
}
