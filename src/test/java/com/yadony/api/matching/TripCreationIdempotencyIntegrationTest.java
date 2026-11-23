package com.yadony.api.matching;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.matching.events.AnnouncementCreatedEvent;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * En-tête {@code Idempotency-Key} de {@code POST /announcements} et {@code POST /announcements/trips}, de bout
 * en bout (MockMvc, H2) : sans en-tête (inchangé), première création, rejeu (un seul trajet, même corps,
 * {@code Idempotent-Replayed}, aucun audit ni événement rejoué), clé mal formée, clé propre au compte, rejeu
 * malgré une limite atteinte par la création d'origine. La course entre deux requêtes simultanées est couverte
 * sur PostgreSQL par {@link TripCreationIdempotencyConcurrencyIT} (H2 n'a pas l'index unique partiel).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@RecordApplicationEvents
@DisplayName("Idempotency-Key : POST /announcements et /announcements/trips (MockMvc)")
class TripCreationIdempotencyIntegrationTest {

    private static final String OWNER = "uid-idem-owner";
    private static final String OTHER = "uid-idem-other";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String REPLAYED = "Idempotent-Replayed";

    @Autowired MockMvc mockMvc;
    @Autowired AnnouncementRepository announcementRepository;
    @Autowired UserRepository userRepository;
    @org.springframework.boot.test.mock.mockito.SpyBean UserRepository spyUsers;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired ApplicationEvents events;

    private static UsernamePasswordAuthenticationToken as(String uid) {
        return new UsernamePasswordAuthenticationToken(uid, null,
                List.of(new SimpleGrantedAuthority("ROLE_TRAVELER")));
    }

    @BeforeEach
    void cleanDb() {
        // H2 ne connaît pas « FOR NO KEY UPDATE » (verrou de la limite mensuelle) : lecture simple à la place.
        org.mockito.Mockito.doAnswer(inv -> spyUsers.findById(inv.getArgument(0)))
                .when(spyUsers).findByIdForUpdate(org.mockito.ArgumentMatchers.any());
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

    private UUID userId(String uid) {
        return userRepository.findByFirebaseUid(uid).orElseThrow().getId();
    }

    private void limitOwnerTo(int limit) {
        UserEntity owner = userRepository.findByFirebaseUid(OWNER).orElseThrow();
        owner.setMonthlyTripLimit(limit);
        owner.setTravelerStatus(com.yadony.api.travelerstatus.TravelerStatus.INDIVIDUAL);
        userRepository.save(owner);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String leg(String from, String to, LocalDate date, boolean draft) {
        return """
            {
              "departureCity": "%s",
              "arrivalCity": "%s",
              "departureDate": "%s",
              "departureTime": "10:00",
              "availableKg": 10,
              "pricePerKg": 8,
              "transportMode": "PLANE",
              "pickupAddress": {"label": "%s", "lat": 48.85, "lng": 2.35},
              "deliveryAddress": {"label": "%s", "lat": 5.35, "lng": -4.0},
              "handoverDeadline": "%sT07:30:00",
              "saveAsDraft": %s
            }
            """.formatted(from, to, date, from, to, date, draft);
    }

    private static String direct(boolean draft) {
        return leg("Paris", "Abidjan", LocalDate.now().plusDays(10), draft);
    }

    private static String twoLegs() {
        LocalDate d = LocalDate.now().plusDays(10);
        return "{\"legs\": [" + leg("Paris", "Abidjan", d, false) + "," + leg("Abidjan", "Douala", d.plusDays(4), false) + "]}";
    }

    private ResultActions postAnnouncement(String uid, String key, String body) throws Exception {
        var request = post("/announcements").with(authentication(as(uid)))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header(KEY_HEADER, key);
        }
        return mockMvc.perform(request);
    }

    private ResultActions postTrip(String uid, String key, String body) throws Exception {
        var request = post("/announcements/trips").with(authentication(as(uid)))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header(KEY_HEADER, key);
        }
        return mockMvc.perform(request);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /**
     * Même corps, champ par champ. Les nombres sont comparés par valeur : la création rend les décimaux de la
     * requête ({@code 10}), le rejeu ceux relus en base ({@code 10.0}), comme {@code GET /announcements/{id}}.
     */
    private static void assertSameBody(JsonNode replay, JsonNode original) {
        java.util.Comparator<JsonNode> byValue = (a, b) -> {
            if (a.isNumber() && b.isNumber()) {
                return a.decimalValue().compareTo(b.decimalValue());
            }
            return a.equals(b) ? 0 : 1;
        };
        assertThat(replay.equals(byValue, original))
                .as("corps du rejeu identique à celui de la création%nrejeu : %s%norigine : %s", replay, original)
                .isTrue();
    }

    private String storedKey(String announcementId) {
        return jdbcTemplate.queryForObject("SELECT idempotency_key FROM announcements WHERE id = ?",
                String.class, UUID.fromString(announcementId));
    }

    private long createdAudits(String uid) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action = 'ANNOUNCEMENT_CREATED'"
                + " AND entity_id = ?", Long.class, userId(uid));
    }

    private long createdEvents() {
        return events.stream(AnnouncementCreatedEvent.class).count();
    }

    // ── POST /announcements ─────────────────────────────────────────────────────

    @Test
    @DisplayName("sans en-tête : comportement inchangé, deux envois = deux trajets, sans en-tête de rejeu")
    void withoutHeader_unchanged() throws Exception {
        String first = json(postAnnouncement(OWNER, null, direct(false))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED)).andReturn()).get("id").asText();
        String second = json(postAnnouncement(OWNER, null, direct(false))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED)).andReturn()).get("id").asText();

        assertThat(second).isNotEqualTo(first);
        assertThat(announcementRepository.count()).isEqualTo(2);
        assertThat(storedKey(first)).isNull();
    }

    @Test
    @DisplayName("en-tête vide : traité comme absent")
    void blankHeader_isLikeNoHeader() throws Exception {
        postAnnouncement(OWNER, "", direct(false))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED));
        assertThat(announcementRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("première création avec clé : 201, clé gardée en base, jamais rendue dans la réponse")
    void firstCreation_keepsTheKeyOutOfTheResponse() throws Exception {
        String key = key();
        MvcResult result = postAnnouncement(OWNER, key, direct(false))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED))
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(key);
        assertThat(storedKey(json(result).get("id").asText())).isEqualTo(key);
    }

    @Test
    @DisplayName("rejeu : même trajet, même corps, Idempotent-Replayed, un seul trajet, ni audit ni événement rejoués")
    void replay_returnsTheSameTripWithoutSideEffects() throws Exception {
        String key = key();
        JsonNode original = json(postAnnouncement(OWNER, key, direct(false))
                .andExpect(status().isCreated()).andReturn());
        long auditsAfterCreation = createdAudits(OWNER);
        long eventsAfterCreation = createdEvents();

        JsonNode replay = json(postAnnouncement(OWNER, key, direct(false))
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED, "true"))
                .andReturn());

        assertSameBody(replay, original);
        assertThat(announcementRepository.count()).isEqualTo(1);
        assertThat(auditsAfterCreation).isEqualTo(1);
        assertThat(createdAudits(OWNER)).isEqualTo(1);
        assertThat(eventsAfterCreation).isEqualTo(1);
        assertThat(createdEvents()).isEqualTo(1);
        assertThat(events.stream(AnnouncementPublishedEvent.class).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("clé mal formée : 400 ProblemDetail idempotency-key-invalid, rien n'est créé")
    void malformedKey_is400() throws Exception {
        for (String bad : List.of("short", "x".repeat(65), "clé avec espaces", "semi;colon-key")) {
            postAnnouncement(OWNER, bad, direct(false))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.code").value("idempotency-key-invalid"))
                    .andExpect(jsonPath("$.type").value("https://yadony.app/errors/idempotency-key-invalid"))
                    .andExpect(jsonPath("$.status").value(400));
        }
        assertThat(announcementRepository.count()).isZero();
    }

    @Test
    @DisplayName("même clé pour deux comptes : chacun crée son trajet")
    void sameKey_twoAccounts_twoTrips() throws Exception {
        String key = key();
        String mine = json(postAnnouncement(OWNER, key, direct(false))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        String theirs = json(postAnnouncement(OTHER, key, direct(false))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED)).andReturn()).get("id").asText();

        assertThat(theirs).isNotEqualTo(mine);
        assertThat(announcementRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("limite mensuelle atteinte par la création d'origine : le rejeu réussit, une vraie nouvelle création est refusée")
    void replay_succeedsWhenTheMonthlyLimitIsReached() throws Exception {
        limitOwnerTo(1);
        String key = key();
        String id = json(postAnnouncement(OWNER, key, direct(false))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();

        postAnnouncement(OWNER, key(), direct(false))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("monthly-limit-reached"));
        postAnnouncement(OWNER, key, direct(false))
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED, "true"))
                .andExpect(jsonPath("$.id").value(id));

        assertThat(announcementRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("limite de brouillons atteinte par la création d'origine : le rejeu du brouillon réussit")
    void replay_succeedsWhenTheDraftLimitIsReached() throws Exception {
        String lastKey = null;
        String lastId = null;
        // Brouillons jusqu'à la limite configurée, quelle qu'elle soit.
        for (int i = 0; i < 30; i++) {
            String key = key();
            MvcResult result = postAnnouncement(OWNER, key, direct(true)).andReturn();
            if (result.getResponse().getStatus() == 403) {
                assertThat(json(result).get("code").asText()).isEqualTo("draft-limit-reached");
                break;
            }
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            lastKey = key;
            lastId = json(result).get("id").asText();
        }
        assertThat(lastKey).as("au moins un brouillon créé avant la limite").isNotNull();
        long drafts = announcementRepository.count();

        postAnnouncement(OWNER, lastKey, direct(true))
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED, "true"))
                .andExpect(jsonPath("$.id").value(lastId))
                .andExpect(jsonPath("$.status").value("DRAFT"));

        assertThat(announcementRepository.count()).isEqualTo(drafts);
    }

    @Test
    @DisplayName("trajet supprimé : sa clé est libérée, la même clé crée un nouveau trajet")
    void deletedTrip_freesItsKey() throws Exception {
        String key = key();
        String id = json(postAnnouncement(OWNER, key, direct(false))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/announcements/" + id).with(authentication(as(OWNER))))
                .andExpect(status().isNoContent());

        String again = json(postAnnouncement(OWNER, key, direct(false))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED)).andReturn()).get("id").asText();

        assertThat(again).isNotEqualTo(id);
    }

    // ── POST /announcements/trips ───────────────────────────────────────────────

    @Test
    @DisplayName("voyage sans en-tête : inchangé, aucune clé sur les étapes")
    void trip_withoutHeader_unchanged() throws Exception {
        JsonNode trip = json(postTrip(OWNER, null, twoLegs())
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED)).andReturn());

        assertThat(storedKey(trip.get("legs").get(0).get("id").asText())).isNull();
        assertThat(storedKey(trip.get("legs").get(1).get("id").asText())).isNull();
    }

    @Test
    @DisplayName("voyage avec clé : seule la première étape la porte ; rejeu = mêmes étapes, dans l'ordre")
    void trip_replay_returnsTheSameLegsInOrder() throws Exception {
        String key = key();
        JsonNode original = json(postTrip(OWNER, key, twoLegs())
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED)).andReturn());
        String leg1 = original.get("legs").get(0).get("id").asText();
        String leg2 = original.get("legs").get(1).get("id").asText();
        assertThat(storedKey(leg1)).isEqualTo(key);
        assertThat(storedKey(leg2)).isNull();
        long eventsAfterCreation = createdEvents();
        long tripAudits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'TRIP_GROUP_CREATED' AND entity_id = ?",
                Long.class, UUID.fromString(original.get("tripGroupId").asText()));

        JsonNode replay = json(postTrip(OWNER, key, twoLegs())
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED, "true"))
                .andReturn());

        assertSameBody(replay, original);
        assertThat(replay.get("legs").get(0).get("id").asText()).isEqualTo(leg1);
        assertThat(replay.get("legs").get(1).get("id").asText()).isEqualTo(leg2);
        assertThat(announcementRepository.count()).isEqualTo(2);
        assertThat(eventsAfterCreation).isEqualTo(2);
        assertThat(createdEvents()).isEqualTo(2);
        assertThat(tripAudits).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action = 'TRIP_GROUP_CREATED' AND entity_id = ?",
                Long.class, UUID.fromString(original.get("tripGroupId").asText()))).isEqualTo(1);
    }

    @Test
    @DisplayName("voyage : clé mal formée → 400, rien n'est créé")
    void trip_malformedKey_is400() throws Exception {
        postTrip(OWNER, "bad key!", twoLegs())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("idempotency-key-invalid"));
        assertThat(announcementRepository.count()).isZero();
    }

    @Test
    @DisplayName("voyage : même clé pour deux comptes → deux voyages")
    void trip_sameKey_twoAccounts() throws Exception {
        String key = key();
        String mine = json(postTrip(OWNER, key, twoLegs()).andExpect(status().isCreated()).andReturn())
                .get("tripGroupId").asText();
        String theirs = json(postTrip(OTHER, key, twoLegs())
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(REPLAYED)).andReturn()).get("tripGroupId").asText();

        assertThat(theirs).isNotEqualTo(mine);
        assertThat(announcementRepository.count()).isEqualTo(4);
    }

    @Test
    @DisplayName("voyage : limite mensuelle atteinte par ses propres étapes → le rejeu réussit quand même")
    void trip_replay_succeedsWhenTheMonthlyLimitIsReached() throws Exception {
        limitOwnerTo(2);
        String key = key();
        String group = json(postTrip(OWNER, key, twoLegs()).andExpect(status().isCreated()).andReturn())
                .get("tripGroupId").asText();

        postTrip(OWNER, key(), twoLegs())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("monthly-limit-reached"));
        postTrip(OWNER, key, twoLegs())
                .andExpect(status().isCreated())
                .andExpect(header().string(REPLAYED, "true"))
                .andExpect(jsonPath("$.tripGroupId").value(group))
                .andExpect(jsonPath("$.legs.length()").value(2));

        assertThat(announcementRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("clé d'un trajet direct renvoyée sur /trips (et inversement) → 409 idempotency-key-reused, rien n'est créé")
    void keyReusedAcrossRoutes_conflict() throws Exception {
        String directKey = key();
        postAnnouncement(OWNER, directKey, direct(false)).andExpect(status().isCreated());
        postTrip(OWNER, directKey, twoLegs())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency-key-reused"));

        String tripKey = key();
        postTrip(OWNER, tripKey, twoLegs()).andExpect(status().isCreated());
        postAnnouncement(OWNER, tripKey, direct(false))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency-key-reused"));

        assertThat(announcementRepository.count()).isEqualTo(3);
    }
}
