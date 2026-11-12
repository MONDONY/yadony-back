package com.yadony.api.matching;

import com.yadony.api.auth.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.config.PlatformSettingKey;
import com.yadony.api.config.PlatformSettingsService;
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
import java.util.List;
import java.util.Map;
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
    @Autowired PlatformSettingsService platformSettings;
    @Autowired org.springframework.cache.CacheManager cacheManager;

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
        // Recherche mise en cache 5 min : deux tests qui lancent la même requête se verraient l'un l'autre.
        var searchCache = cacheManager.getCache("announcements-search");
        if (searchCache != null) {
            searchCache.clear();
        }
        announcementRepository.deleteAll();
        userRepository.deleteAll();
        // Le bateau est fermé par défaut : ces tests créent des trajets BOAT, il faut l'ouvrir.
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "true"), UUID.randomUUID());
    }

    @AfterEach
    void closeBoat() {
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "false"), UUID.randomUUID());
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

    // ─── taille minimale et ville de passage (route) ─────────────────────────

    @Test
    void searchAnnouncements_minItemSize_keepsLargeEnoughTripsOnly_andIgnoresUnknownValue() throws Exception {
        sizedTrip("Paris", "Dakar", ItemSize.M, null);
        sizedTrip("Paris", "Dakar", ItemSize.L, null);
        sizedTrip("Paris", "Dakar", null, null);

        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("minItemSize", "l"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1));
        // Valeur inconnue : filtre ignoré (ancienne app, saisie libre), les 3 trajets reviennent.
        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("minItemSize", "XXL"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    void searchAnnouncements_viaCity_keepsLegsOfVoyagesStoppingThere_andBlankIsIgnored() throws Exception {
        UUID voyage = UUID.randomUUID();
        sizedTrip("Paris", "Tanger", null, voyage);
        sizedTrip("Tanger", "Dakar", null, voyage);
        sizedTrip("Paris", "Dakar", null, null);

        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("viaCity", "TANGER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(2));
        // Même requête sans le paramètre juste après : la clé de cache distingue les deux.
        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(3));
        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("viaCity", "  "))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    void searchAnnouncements_viaCityWithDepartureAndArrival_appliesEachToTheLeg() throws Exception {
        UUID voyage = UUID.randomUUID();
        sizedTrip("Paris", "Tanger", null, voyage);
        sizedTrip("Tanger", "Dakar", null, voyage);

        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("departureCity", "Paris")
                .param("viaCity", "Tanger"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].arrivalCity").value("Tanger"));
    }

    @Test
    void searchAnnouncements_minAvailableKg1000_returnsTonneTrip() throws Exception {
        AnnouncementEntity tonne = sizedTrip("Paris", "Dakar", ItemSize.XL, null);
        tonne.setAvailableKg(new BigDecimal("1000"));
        tonne.setTotalKg(new BigDecimal("1000"));
        announcementRepository.save(tonne);

        mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .param("minAvailableKg", "1000"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content.length()").value(1));
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

        // Tri par défaut = score : à voyageur égal, le départ le plus proche (fraîcheur) passe
        // devant ; le trajet urgent (départ+1j) apparaît avant le non-urgent (départ+10j).
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
        // Le bateau est réservé aux transporteurs vérifiés : GP Pro ici.
        var boatCarrier = seedTraveler("uid-test-traveler");
        boatCarrier.setGpPro(true);
        userRepository.save(boatCarrier);
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
    void createAnnouncement_boatWhenSwitchOff_returns422() throws Exception {
        seedTraveler("uid-test-traveler");
        platformSettings.update(Map.of(PlatformSettingKey.TRANSPORT_BOAT_ENABLED, "false"), UUID.randomUUID());

        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validBodyWithMode("BOAT")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("transport-mode-disabled"));
    }

    @Test
    void searchAnnouncements_returnsTransportModeInResults() throws Exception {
        var boatCarrier = seedTraveler("uid-test-traveler");
        boatCarrier.setGpPro(true);
        userRepository.save(boatCarrier);
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

    // ─── Capacité en tonnes (V380, WeightLimits) ──────────────────────────────

    @Test
    void post_trip_availableKg1000000_rejected422() throws Exception {
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithModeAndKg("CAR", "1000000")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.violations.availableKg").exists());
    }

    @Test
    void post_planeTrip_1000kg_returns422TripCapacityTooHigh() throws Exception {
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithModeAndKg("PLANE", "1000")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("trip-capacity-too-high"));
    }

    @Test
    void detail_serializes1200kgAsPlainNumber() throws Exception {
        seedTraveler("uid-test-traveler");
        var createRes = mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithModeAndKg("CAR", "1200")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.availableKg").value(1200.0))
            .andReturn();
        String id = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        var detail = mockMvc.perform(get("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableKg").value(1200.0))
            .andReturn();
        // Nombre décimal ordinaire (ni chaîne, ni notation scientifique) : les anciennes apps le lisent.
        JsonNode kg = objectMapper.readTree(detail.getResponse().getContentAsString()).get("availableKg");
        assertThatNumber(kg);
    }

    // ─── Taille max et poids max par colis (V381, ParcelFitRules) ─────────────

    private String bodyWithLimits(String mode, String limitsJson) {
        String date = LocalDate.now().plusDays(10).toString();
        return """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 500,
              "pricePerKg": 5,
              "transportMode": "%s",
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447},
              "handoverDeadline": "%sT07:30:00"%s
            }
            """.formatted(date, mode, date, limitsJson);
    }

    @Test
    void detail_alwaysSerializesMaxItemSizeAndMaxParcelWeight() throws Exception {
        seedTraveler("uid-test-traveler");
        // Trajet avion, sans limite : les deux clés sont là, à null (pas absentes).
        var createRes = mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("PLANE", "")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.maxItemSize").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(org.hamcrest.Matchers.nullValue()))
            .andReturn();
        String id = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        var detail = mockMvc.perform(get("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxItemSize").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(org.hamcrest.Matchers.nullValue()))
            .andReturn();
        JsonNode json = objectMapper.readTree(detail.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(json.has("maxItemSize")).isTrue();
        org.assertj.core.api.Assertions.assertThat(json.has("maxParcelWeightKg")).isTrue();
    }

    @Test
    void search_alwaysSerializesMaxItemSizeAndMaxParcelWeight() throws Exception {
        seedAnnouncement(48.8566, 2.3522, "Paris", "Dakar");

        var res = mockMvc.perform(get("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler"))))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode first = objectMapper.readTree(res.getResponse().getContentAsString()).get("content").get(0);
        org.assertj.core.api.Assertions.assertThat(first.has("maxItemSize")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.get("maxItemSize").isNull()).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.has("maxParcelWeightKg")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.get("maxParcelWeightKg").isNull()).isTrue();
    }

    @Test
    void create_roadTripWithLimits_isStoredAndServedByCreateAndDetail() throws Exception {
        seedTraveler("uid-test-traveler");
        var createRes = mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("CAR", ", \"maxItemSize\": \"L\", \"maxParcelWeightKg\": 20.5")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.maxItemSize").value("L"))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(20.5))
            .andExpect(jsonPath("$.capacityUnit").value("KG_EXACT"))
            .andReturn();
        String id = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxItemSize").value("L"))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(20.5));
    }

    @Test
    void create_planeTripWithLimits_ignoresThem() throws Exception {
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("PLANE", ", \"maxItemSize\": \"L\", \"maxParcelWeightKg\": 20")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.maxItemSize").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.capacityUnit").value("SUITCASE_23KG"));
    }

    @Test
    void update_roadToPlane_clearsLimits() throws Exception {
        seedTraveler("uid-test-traveler");
        var createRes = mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("CAR", ", \"maxItemSize\": \"M\", \"maxParcelWeightKg\": 30")))
            .andExpect(status().isCreated())
            .andReturn();
        String id = objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("PLANE", ", \"maxItemSize\": \"M\", \"maxParcelWeightKg\": 30")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxItemSize").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(org.hamcrest.Matchers.nullValue()));
    }

    private String createRoadTripWithLimits(String uid) throws Exception {
        var createRes = mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs(uid)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("CAR", ", \"maxItemSize\": \"M\", \"maxParcelWeightKg\": 30")))
            .andExpect(status().isCreated())
            .andReturn();
        return objectMapper.readTree(createRes.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void update_roadWithoutLimitFields_keepsLimits() throws Exception {
        seedTraveler("uid-test-traveler");
        String id = createRoadTripWithLimits("uid-test-traveler");

        // Ancienne app : le PUT n'envoie ni taille ni poids max.
        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("CAR", "")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxItemSize").value("M"))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(30.0));
        mockMvc.perform(get("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxItemSize").value("M"))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(30.0));
    }

    @Test
    void update_roadWithNoneAndZero_clearsLimits() throws Exception {
        seedTraveler("uid-test-traveler");
        String id = createRoadTripWithLimits("uid-test-traveler");

        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("CAR", ", \"maxItemSize\": \"NONE\", \"maxParcelWeightKg\": 0")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxItemSize").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxItemSize").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void update_roadToPlaneWithoutLimitFields_clearsLimits() throws Exception {
        seedTraveler("uid-test-traveler");
        String id = createRoadTripWithLimits("uid-test-traveler");

        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("PLANE", "")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.maxItemSize").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void update_invalidLimits_rejected422AndLimitsUntouched() throws Exception {
        seedTraveler("uid-test-traveler");
        String id = createRoadTripWithLimits("uid-test-traveler");

        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("CAR", ", \"maxParcelWeightKg\": 0.3")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.violations.maxParcelWeightKg").exists());
        mockMvc.perform(put("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("CAR", ", \"maxItemSize\": \"XXL\"")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.violations.maxItemSize").exists());
        mockMvc.perform(get("/announcements/" + id)
                .with(authentication(authenticatedAs("uid-test-traveler"))))
            .andExpect(jsonPath("$.maxItemSize").value("M"))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(30.0));
    }

    @Test
    void create_noneAndZero_acceptedAsNoLimit() throws Exception {
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("CAR", ", \"maxItemSize\": \"NONE\", \"maxParcelWeightKg\": 0")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.maxItemSize").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.maxParcelWeightKg").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void create_maxParcelWeightAbove999999_99_rejected422() throws Exception {
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("CAR", ", \"maxParcelWeightKg\": 1000000")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.violations.maxParcelWeightKg").exists());
    }

    @Test
    void create_maxParcelWeightBelowHalfKilo_rejected422() throws Exception {
        seedTraveler("uid-test-traveler");
        mockMvc.perform(post("/announcements")
                .with(authentication(authenticatedAs("uid-test-traveler")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyWithLimits("CAR", ", \"maxParcelWeightKg\": 0.4")))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.violations.maxParcelWeightKg").exists());
    }

    @Test
    void create_maxParcelWeightHalfKiloAndMax_accepted() throws Exception {
        seedTraveler("uid-test-traveler");
        for (String kg : List.of("0.5", "999999.99")) {
            announcementRepository.deleteAll();
            mockMvc.perform(post("/announcements")
                    .with(authentication(authenticatedAs("uid-test-traveler")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bodyWithLimits("CAR", ", \"maxParcelWeightKg\": " + kg)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxParcelWeightKg").value(Double.parseDouble(kg)));
        }
    }

    @Test
    void create_unknownOrLowercaseItemSize_rejected422() throws Exception {
        seedTraveler("uid-test-traveler");
        for (String size : List.of("XXL", "l", "")) {
            mockMvc.perform(post("/announcements")
                    .with(authentication(authenticatedAs("uid-test-traveler")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(bodyWithLimits("CAR", ", \"maxItemSize\": \"" + size + "\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.violations.maxItemSize").exists());
        }
    }

    private static void assertThatNumber(JsonNode node) {
        org.assertj.core.api.Assertions.assertThat(node.isNumber()).isTrue();
        org.assertj.core.api.Assertions.assertThat(node.asText()).doesNotContain("E").doesNotContain("e");
    }

    private String bodyWithModeAndKg(String mode, String kg) {
        String date = LocalDate.now().plusDays(10).toString();
        return """
            {
              "departureCity": "Paris",
              "arrivalCity": "Dakar",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": %s,
              "pricePerKg": 5,
              "transportMode": "%s",
              "capacityUnit": "KG_EXACT",
              "pickupAddress": {"label": "Lyon", "lat": 45.748, "lng": 4.846},
              "deliveryAddress": {"label": "Dakar", "lat": 14.693, "lng": -17.447},
              "handoverDeadline": "%sT07:30:00"
            }
            """.formatted(date, kg, mode, date);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Trajet au kilo ; en bateau, avec la fourchette d'arrivée estimée obligatoire depuis le lot 3. */
    private String validBodyWithMode(String mode) {
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
              "handoverDeadline": "%sT07:30:00"%s
            }
            """.formatted(date, mode, date, range);
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

    private AnnouncementEntity sizedTrip(String dep, String arr, ItemSize maxSize, UUID tripGroupId) {
        AnnouncementEntity e = seedAnnouncement(48.8566, 2.3522, dep, arr);
        e.setMaxItemSize(maxSize);
        e.setTripGroupId(tripGroupId);
        if (tripGroupId != null) {
            e.setTripLegIndex(1);
        }
        return announcementRepository.save(e);
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
