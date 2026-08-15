package com.yadony.api.matching;

import com.yadony.api.auth.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
class AnnouncementControllerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @Autowired ObjectMapper objectMapper;

    /**
     * Reuse the same traveler UUID across all tests (no UserEntity needed;
     * toSearchResponse handles Optional.empty() for the traveler profile).
     */
    private static final UUID testTravelerId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * Auth helper: matches what FirebaseTokenFilter puts in the SecurityContext
     * (a String principal = Firebase UID).
     */
    private static UsernamePasswordAuthenticationToken authenticatedAs(String uid) {
        return new UsernamePasswordAuthenticationToken(
                uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @BeforeEach
    void cleanDb() {
        announcementRepository.deleteAll();
        userRepository.deleteAll();
    }

    // ─── Radius filter tests ──────────────────────────────────────────────────

    @Test
    void searchAnnouncements_withRadius_returnsOnlyNearby() throws Exception {
        // GIVEN: 3 Paris→Dakar announcements at known pickup coords
        seedAnnouncement(48.8566, 2.3522, "Paris", "Dakar");   // Paris center
        seedAnnouncement(48.8600, 2.3500, "Paris", "Dakar");   // ~0.5 km from Paris1
        seedAnnouncement(45.7640, 4.8357, "Paris", "Dakar");   // Lyon coords (~400 km from Paris)

        // WHEN: search with userLat/Lng=Paris center, radius=10km
        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("userLat", "48.8566")
                .param("userLng", "2.3522")
                .param("radiusKm", "10"))
            .andExpect(status().isOk())
            // THEN: only the 2 Paris-area announcements are returned
            .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void searchAnnouncements_withRadius_excludesFarAnnouncements() throws Exception {
        // GIVEN: 1 announcement far from the user + 1 announcement nearby
        // Far: Lyon (~400 km from Paris)
        seedAnnouncement(45.7640, 4.8357, "Paris", "Dakar");
        // Nearby: Paris center
        seedAnnouncement(48.8566, 2.3522, "Paris", "Dakar");

        // WHEN: search with radius=100km around Paris
        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("userLat", "48.8566")
                .param("userLng", "2.3522")
                .param("radiusKm", "100"))
            .andExpect(status().isOk())
            // THEN: only the 1 Paris announcement (Lyon is ~400 km away, outside 100 km)
            .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void searchAnnouncements_withRadius_combinesWithCityFilter() throws Exception {
        // GIVEN: a Paris→Dakar near user + a Lyon→Dakar near user
        // (coords forced near Paris to isolate the city-filter logic)
        seedAnnouncement(48.8566, 2.3522, "Paris", "Dakar");
        seedAnnouncement(48.8600, 2.3500, "Lyon",  "Dakar"); // pickup near Paris but city=Lyon

        // WHEN: search with city=Paris + radius=5km
        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("departureCity", "Paris")
                .param("userLat", "48.8566")
                .param("userLng", "2.3522")
                .param("radiusKm", "5"))
            .andExpect(status().isOk())
            // THEN: only the Paris one (AND semantics — city filter AND radius filter)
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].departureCity").value("Paris"));
    }

    // ─── urgent filter ──────────────────────────────────────────────────────

    @Test
    void search_urgentTrue_returnsOnlyImminentDepartures() throws Exception {
        var traveler = seedTraveler("uid-test-traveler-urgent");
        // Urgente : départ demain (dans la fenêtre [today, today+3] — seuil de test).
        seedAnnouncementForTravelerWithDate(traveler.getId(), LocalDate.now().plusDays(1));
        // Non urgente : départ dans 10 jours.
        seedAnnouncementForTravelerWithDate(traveler.getId(), LocalDate.now().plusDays(10));

        mockMvc.perform(get("/announcements").param("urgent", "true")
                        .with(authentication(authenticatedAs("uid-test-traveler-urgent"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[*].urgent", org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(true))));
    }

    @Test
    void search_urgentNotSet_stillExposesUrgentFlagPerAnnouncement() throws Exception {
        var traveler = seedTraveler("uid-test-traveler-urgent-flag");
        seedAnnouncementForTravelerWithDate(traveler.getId(), LocalDate.now().plusDays(1));
        seedAnnouncementForTravelerWithDate(traveler.getId(), LocalDate.now().plusDays(10));

        // Tri par défaut = date ASC : le trajet urgent (départ+1j) apparaît avant le non-urgent (départ+10j).
        mockMvc.perform(get("/announcements")
                        .with(authentication(authenticatedAs("uid-test-traveler-urgent-flag"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].urgent").value(true))
                .andExpect(jsonPath("$.content[1].urgent").value(false));
    }

    // ─── Create — transportMode validation ────────────────────────────────────

    @Test
    void createAnnouncement_withMissingTransportMode_returns422() throws Exception {
        // GIVEN: a valid create payload that OMITS transportMode
        var body = """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 10,
              "pricePerKg": 5,
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447}
            }
            """.formatted(LocalDate.now().plusDays(10));

        // WHEN: POST /announcements without transportMode
        // THEN: validation rejects the payload (RFC 7807, 422 per GlobalExceptionHandler)
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isUnprocessableEntity());
    }

    // ─── Create — transportMode happy paths ───────────────────────────────────

    @Test
    void createAnnouncement_withEachTransportMode_returns201() throws Exception {
        seedTraveler("uid-test-traveler");
        for (var mode : List.of("PLANE", "CAR", "TRAIN", "BUS", "BOAT", "OTHER")) {
            announcementRepository.deleteAll();
            mockMvc.perform(post("/announcements")
                    .with(authentication(authenticatedAs("uid-test-traveler")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validBodyWithMode(mode)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transportMode").value(mode));
        }
    }

    @Test
    void createAnnouncement_exposesPricePerKgDisplay_netPlus12Percent() throws Exception {
        // Cohérence des prix : le DTO expose pricePerKgDisplay = pricePerKg (net) × 1.12,
        // symétrique de unitPriceDisplay du mode MIXED, pour l'affichage côté expéditeur.
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("PLANE")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.pricePerKgDisplay").value(5.60));
    }

    @Test
    void createAnnouncement_withCountryCodes_exposesCodesAndFlags() throws Exception {
        // Les codes pays ISO-2 envoyés à la création sont persistés et renvoyés,
        // accompagnés de leurs drapeaux emoji résolus par FlagService (US → 🇺🇸).
        seedTraveler("uid-test-traveler");
        String date = LocalDate.now().plusDays(10).toString();
        String body = """
            {
              "departureCity": "New York",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 10,
              "pricePerKg": 5,
              "transportMode": "PLANE",
              "pickupAddress": {"label": "JFK", "lat": 40.641, "lng": -73.778},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447},
              "departureCountryCode": "US",
              "arrivalCountryCode": "SN",
              "handoverDeadline": "%sT07:30:00"
            }
            """.formatted(date, date);
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.departureCountryCode").value("US"))
            .andExpect(jsonPath("$.arrivalCountryCode").value("SN"))
            .andExpect(jsonPath("$.departureFlag").value("🇺🇸"))
            .andExpect(jsonPath("$.arrivalFlag").value("🇸🇳"));
    }

    @Test
    void createAnnouncement_withKgExactCapacityUnit_returns201() throws Exception {
        // Régression : capacityUnit "KG_EXACT" (capacité personnalisée saisie par le
        // voyageur) doit être désérialisé sans 400 « Malformed request payload ».
        seedTraveler("uid-test-traveler");
        String date = LocalDate.now().plusDays(10).toString();
        String body = """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 15,
              "pricePerKg": 5,
              "transportMode": "PLANE",
              "capacityUnit": "KG_EXACT",
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447},
              "handoverDeadline": "%sT07:30:00"
            }
            """.formatted(date, date);
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.capacityUnit").value("KG_EXACT"))
            .andExpect(jsonPath("$.availableKg").value(15));
    }

    @Test
    void createAnnouncement_withInvalidTransportMode_returns400() throws Exception {
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("BIKE")))
            .andExpect(status().isBadRequest());
    }

    @Test
    void updateAnnouncement_changesTransportMode_returns200() throws Exception {
        seedTraveler("uid-test-traveler");
        var createRes = mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("PLANE")))
            .andExpect(status().isCreated())
            .andReturn();
        JsonNode created = objectMapper.readTree(createRes.getResponse().getContentAsString());
        String id = created.get("id").asText();

        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("TRAIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.transportMode").value("TRAIN"));
    }

    @Test
    void searchAnnouncements_returnsTransportModeInResults() throws Exception {
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("BOAT")))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].transportMode").value("BOAT"));
    }

    // ─── Handover window — Z-suffix serialization contract ───────────────────

    /**
     * Flutter sends ISO-8601 strings with a trailing 'Z' UTC suffix
     * (e.g. "2026-06-14T06:00:00.000Z"). The backend deserializes them into
     * LocalDateTime; this test verifies the round-trip is lossless: no offset
     * shift occurs, no 422 is produced, and the response echoes the same
     * wall-clock value with a 'Z' suffix (our UtcLocalDateTimeSerializer).
     */
    @Test
    void createAnnouncement_handoverDeadlineWithZSuffix_roundTripsWithoutShift() throws Exception {
        seedTraveler("uid-test-traveler");
        String date = LocalDate.now().plusDays(30).toString();
        String body = """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 10,
              "pricePerKg": 5,
              "transportMode": "PLANE",
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447},
              "handoverDeadline": "%sT07:30:00.000Z"
            }
            """.formatted(date, date);

        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            // Wall-clock time must be preserved: 06:00 in, 06:00:00Z out (no TZ shift)
            .andExpect(jsonPath("$.handoverDeadline").value(date + "T07:30:00Z"));
    }

    // ─── POST /announcements/{id}/publish ──────────────────────────────────────

    @Test
    void publishDraft_returns200_andAnnouncementBecomesActive() throws Exception {
        var traveler = seedTraveler("uid-traveler-publish");
        UUID draftId = seedAnnouncementForTraveler(traveler.getId(), AnnouncementStatus.DRAFT).getId();

        mockMvc.perform(post("/announcements/" + draftId + "/publish")
                .with(authentication(authenticatedAs("uid-traveler-publish"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void publishActive_returns422NotADraft() throws Exception {
        var traveler = seedTraveler("uid-traveler-active");
        UUID activeId = seedAnnouncementForTraveler(traveler.getId(), AnnouncementStatus.ACTIVE).getId();

        mockMvc.perform(post("/announcements/" + activeId + "/publish")
                .with(authentication(authenticatedAs("uid-traveler-active"))))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.containsString("not-a-draft")));
    }

    @Test
    void publishSomeoneElsesDraft_isRejected() throws Exception {
        var owner = seedTraveler("uid-traveler-owner");
        seedTraveler("uid-traveler-other");
        UUID draftId = seedAnnouncementForTraveler(owner.getId(), AnnouncementStatus.DRAFT).getId();

        mockMvc.perform(post("/announcements/" + draftId + "/publish")
                .with(authentication(authenticatedAs("uid-traveler-other"))))
            .andExpect(status().is4xxClientError());
    }

    @Test
    void createDraft_thenListMyDrafts_returnsIt() throws Exception {
        seedTraveler("uid-test-traveler");

        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBodyWithMode("PLANE")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"));

        mockMvc.perform(get("/announcements/my")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("status", "DRAFT"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].status").value("DRAFT"));
    }

    @Test
    void draftIsInvisibleInPublicSearch() throws Exception {
        var traveler = seedTraveler("uid-traveler-draft-search");
        seedAnnouncementForTraveler(traveler.getId(), AnnouncementStatus.DRAFT);

        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-traveler-draft-search")))
                .param("departureCity", "Paris")
                .param("arrivalCity", "Dakar"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    void publicTravelerAnnouncements_excludeDrafts() throws Exception {
        var traveler = seedTraveler("uid-traveler-public-profile");
        seedAnnouncementForTraveler(traveler.getId(), AnnouncementStatus.DRAFT);
        seedAnnouncementForTraveler(traveler.getId(), AnnouncementStatus.ACTIVE);

        // Endpoint public : accessible sans authentification traveler-owner
        // (profil public d'un voyageur, consultable par n'importe quel utilisateur).
        mockMvc.perform(get("/travelers/" + traveler.getId() + "/announcements")
                .with(authentication(authenticatedAs("uid-some-other-user"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    // ─── DELETE /announcements/{id} — suppression d'un brouillon ──────────────

    @Test
    void deleteDraft_owner_returns204_andFreesQuota() throws Exception {
        var traveler = seedTraveler("uid-traveler-draft-delete");
        UUID draftId = seedAnnouncementForTraveler(traveler.getId(), AnnouncementStatus.DRAFT).getId();

        mockMvc.perform(delete("/announcements/" + draftId)
                .with(authentication(authenticatedAs("uid-traveler-draft-delete"))))
            .andExpect(status().isNoContent());

        // Le quota (1 brouillon pour un compte standard) doit être libéré : recréer
        // un brouillon doit repasser, sinon le premier brouillon supprimé le bloquerait à vie.
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-traveler-draft-delete")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(draftBodyWithMode("PLANE")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    // ─── GET /announcements/{id} — confidentialité des brouillons ─────────────

    @Test
    void getDraft_owner_returns200() throws Exception {
        var traveler = seedTraveler("uid-traveler-draft-detail-owner");
        UUID draftId = seedAnnouncementForTraveler(traveler.getId(), AnnouncementStatus.DRAFT).getId();

        mockMvc.perform(get("/announcements/" + draftId)
                .with(authentication(authenticatedAs("uid-traveler-draft-detail-owner"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void getDraft_otherTraveler_returns404_withoutLeakingExistence() throws Exception {
        var owner = seedTraveler("uid-traveler-draft-detail-owner2");
        seedTraveler("uid-traveler-draft-detail-other");
        UUID draftId = seedAnnouncementForTraveler(owner.getId(), AnnouncementStatus.DRAFT).getId();

        mockMvc.perform(get("/announcements/" + draftId)
                .with(authentication(authenticatedAs("uid-traveler-draft-detail-other"))))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.containsString("announcement-not-found")));
    }

    @Test
    void getActive_otherTraveler_returns200_nonRegression() throws Exception {
        var owner = seedTraveler("uid-traveler-active-detail-owner");
        seedTraveler("uid-traveler-active-detail-other");
        UUID activeId = seedAnnouncementForTraveler(owner.getId(), AnnouncementStatus.ACTIVE).getId();

        mockMvc.perform(get("/announcements/" + activeId)
                .with(authentication(authenticatedAs("uid-traveler-active-detail-other"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private String validBodyWithMode(String mode) {
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
              "handoverDeadline": "%sT07:30:00"
            }
            """.formatted(date, mode, date);
    }

    private String draftBodyWithMode(String mode) {
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
              "handoverDeadline": "%sT07:30:00",
              "saveAsDraft": true
            }
            """.formatted(date, mode, date);
    }

    private AnnouncementEntity seedAnnouncementForTraveler(UUID travelerId, AnnouncementStatus status) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(travelerId);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDepartureDate(LocalDate.now().plusDays(7));
        e.setAvailableKg(new BigDecimal("8"));
        e.setTotalKg(new BigDecimal("8"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setStatus(status);
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(48.8566));
        e.setPickupLng(BigDecimal.valueOf(2.3522));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e);
    }

    /** Comme {@link #seedAnnouncementForTraveler} mais avec une date de départ paramétrable (filtre urgent). */
    private AnnouncementEntity seedAnnouncementForTravelerWithDate(UUID travelerId, LocalDate departureDate) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(travelerId);
        e.setDepartureCity("Paris");
        e.setArrivalCity("Dakar");
        e.setDepartureDate(departureDate);
        e.setAvailableKg(new BigDecimal("8"));
        e.setTotalKg(new BigDecimal("8"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setStatus(AnnouncementStatus.ACTIVE);
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(48.8566));
        e.setPickupLng(BigDecimal.valueOf(2.3522));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e);
    }

    private com.yadony.api.auth.UserEntity seedTraveler(String firebaseUid) {
        var user = new com.yadony.api.auth.UserEntity();
        user.setFirebaseUid(firebaseUid);
        // Dérivé du firebaseUid (et non une constante) : certains tests seedent plusieurs
        // voyageurs (ex. propriétaire + tiers pour un contrôle d'ownership) — un numéro fixe
        user.setStatus(com.yadony.api.auth.UserStatus.ACTIVE);
        user.setKycStatus(com.yadony.api.auth.KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(List.of(com.yadony.api.auth.Role.TRAVELER)));
        return userRepository.save(user);
    }

    private AnnouncementEntity seedAnnouncement(double lat, double lng, String dep, String arr) {
        AnnouncementEntity e = new AnnouncementEntity();
        e.setTravelerId(testTravelerId);
        e.setDepartureCity(dep);
        e.setArrivalCity(arr);
        e.setDepartureDate(LocalDate.now().plusDays(7));
        e.setAvailableKg(new BigDecimal("8"));
        e.setTotalKg(new BigDecimal("8"));
        e.setPricePerKg(new BigDecimal("12"));
        e.setStatus(AnnouncementStatus.ACTIVE);
        e.setTransportMode(TransportMode.PLANE);
        e.setPickupAddressLabel("Test pickup");
        e.setPickupLat(BigDecimal.valueOf(lat));
        e.setPickupLng(BigDecimal.valueOf(lng));
        e.setDeliveryAddressLabel("Test delivery");
        e.setDeliveryLat(BigDecimal.valueOf(14.6928));
        e.setDeliveryLng(BigDecimal.valueOf(-17.4467));
        return announcementRepository.save(e);
    }
}
