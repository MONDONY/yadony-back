package com.yadony.api.videomodal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.i18n.AppLanguage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Moments d'action de bout en bout sur H2 (profil test) : routes admin (création, relecture,
 * modification, validation 422 ProblemDetail) et {@code GET /video-modals/eligible} avec et sans
 * {@code caps=moments}, visiteur et compte connecté.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Modals : moments d'action (/video-modals et /admin/video-modals)")
class VideoModalMomentsIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired VideoModalCampaignRepository campaignRepository;
    @Autowired VideoModalResponseRepository responseRepository;
    @MockitoBean AuditService auditService;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String OLD_APP = "layout,targeting";
    private static final String NEW_APP = "layout,targeting,moments";
    private String uid;

    @BeforeEach
    void setUp() {
        responseRepository.deleteAll();
        campaignRepository.deleteAll();
        uid = "uid-moments-" + UUID.randomUUID();
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setFirstName("Awa");
        u.setLastName("Koné");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setTotalTrips(0);
        u.setPreferredLanguage(AppLanguage.FR);
        userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-moments"),
                null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private UsernamePasswordAuthenticationToken userAuth() {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static String body(String triggers, String extra) {
        return """
                {"name":"Campagne","youtubeUrl":"https://youtu.be/dQw4w9WgXcQ","durationSeconds":204,
                 "titleFr":"Bienvenue","titleEn":"Welcome","highlights":[],
                 "triggers":%s,"maxDisplays":3%s}
                """.formatted(triggers, extra == null || extra.isBlank() ? "" : "," + extra);
    }

    private JsonNode create(String triggers, String extra) throws Exception {
        String json = mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(triggers, extra)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private String createActive(String triggers, String extra) throws Exception {
        String id = create(triggers, extra).get("id").asText();
        mockMvc.perform(patch("/admin/video-modals/{id}/active", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        return id;
    }

    private JsonNode eligible(boolean connected, String caps) throws Exception {
        var request = get("/video-modals/eligible");
        if (connected) request = request.with(authentication(userAuth()));
        if (caps != null) request = request.param("caps", caps);
        String json = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    // ── Admin ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("création avec moments, seuils et corridors : 201, relecture, modification, audit")
    void adminCreationRelectureModification() throws Exception {
        String extra = """
                "triggerOptions":{"fewResultsMax":5,"tripViewedTimes":4},
                "corridors":[{"from":"Paris","to":"Abidjan"},{"to":"Dakar"},{"from":"Lyon","to":null}]
                """;
        JsonNode created = create("[\"SEARCH_FEW_RESULTS\",\"TRIP_VIEWED_REPEATEDLY\",\"DELIVERY_CONFIRMED\"]", extra);

        assertThat(texts(created.get("triggers"))).containsExactly("SEARCH_FEW_RESULTS", "TRIP_VIEWED_REPEATEDLY", "DELIVERY_CONFIRMED");
        assertThat(created.get("triggerOptions").get("fewResultsMax").asInt()).isEqualTo(5);
        assertThat(created.get("triggerOptions").get("tripViewedTimes").asInt()).isEqualTo(4);
        assertThat(created.get("corridors")).hasSize(3);
        assertThat(created.get("corridors").get(0).get("from").asText()).isEqualTo("Paris");
        assertThat(created.get("corridors").get(0).get("to").asText()).isEqualTo("Abidjan");
        assertThat(created.get("corridors").get(1).path("from").isMissingNode()).isTrue(); // côté nul : champ omis
        assertThat(created.get("corridors").get(1).get("to").asText()).isEqualTo("Dakar");
        UUID id = UUID.fromString(created.get("id").asText());
        verify(auditService).log(eq("VIDEO_MODAL"), eq(id), eq("VIDEO_MODAL_CREATED"), eq(ADMIN_ID), any());

        mockMvc.perform(get("/admin/video-modals/{id}", id).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggerOptions.fewResultsMax").value(5))
                .andExpect(jsonPath("$.corridors.length()").value(3))
                .andExpect(jsonPath("$.corridors[2].from").value("Lyon"));
        mockMvc.perform(get("/admin/video-modals").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].triggers[2]").value("DELIVERY_CONFIRMED"));

        // Modification : retour aux défauts quand les champs sont absents.
        mockMvc.perform(put("/admin/video-modals/{id}", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("[\"PAYMENT_FAILED\"]", null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.triggers[0]").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.triggerOptions.fewResultsMax").value(2))
                .andExpect(jsonPath("$.triggerOptions.tripViewedTimes").value(3))
                .andExpect(jsonPath("$.corridors.length()").value(0));
        verify(auditService).log(eq("VIDEO_MODAL"), eq(id), eq("VIDEO_MODAL_UPDATED"), eq(ADMIN_ID), any());
    }

    @Test
    @DisplayName("ancien corps (sans moments) : toujours accepté, seuils 2 et 3, aucun corridor")
    void ancienCorps() throws Exception {
        JsonNode created = create("[\"SEARCH_TAB\"]", null);
        assertThat(created.get("triggerOptions").get("fewResultsMax").asInt()).isEqualTo(2);
        assertThat(created.get("triggerOptions").get("tripViewedTimes").asInt()).isEqualTo(3);
        assertThat(created.get("corridors")).isEmpty();
    }

    @Test
    @DisplayName("validation : 422 ProblemDetail pour chaque borne (seuils, onze corridors, 81 caractères, entrée nulle ou sans aucun côté)")
    void adminValidation() throws Exception {
        StringBuilder eleven = new StringBuilder("\"corridors\":[");
        for (int i = 0; i < 11; i++) eleven.append(i == 0 ? "" : ",").append("{\"from\":\"V").append(i).append("\"}");
        eleven.append("]");
        String longName = "a".repeat(81);
        List<String> invalid = List.of(
                "\"triggerOptions\":{\"fewResultsMax\":0}",
                "\"triggerOptions\":{\"fewResultsMax\":21}",
                "\"triggerOptions\":{\"tripViewedTimes\":1}",
                "\"triggerOptions\":{\"tripViewedTimes\":11}",
                eleven.toString(),
                "\"corridors\":[{\"from\":\"" + longName + "\"}]",
                "\"corridors\":[{\"to\":\"" + longName + "\"}]",
                "\"corridors\":[null]",
                "\"corridors\":[{\"from\":null,\"to\":null}]",
                "\"corridors\":[{\"from\":\" \",\"to\":\"\"}]",
                "\"corridors\":[{}]");
        for (String extra : invalid) {
            mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                            .contentType(MediaType.APPLICATION_JSON).content(body("[\"SEARCH_FEW_RESULTS\"]", extra)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        assertThat(campaignRepository.count()).isZero();
        // Bornes incluses : acceptées.
        create("[\"SEARCH_FEW_RESULTS\"]", "\"triggerOptions\":{\"fewResultsMax\":20,\"tripViewedTimes\":10}");
        create("[\"SEARCH_FEW_RESULTS\"]", "\"triggerOptions\":{\"fewResultsMax\":1,\"tripViewedTimes\":2}");
        assertThat(campaignRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("déclencheur inconnu : refusé par la désérialisation (400), comme les autres énumérations")
    void declencheurInconnu() throws Exception {
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("[\"SEARCH_SOMETHING\"]", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("non-admin : 403 ; sans jeton : 401")
    void adminForbidden() throws Exception {
        mockMvc.perform(post("/admin/video-modals").with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("[\"KYC_VERIFIED\"]", null)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/video-modals")
                        .contentType(MediaType.APPLICATION_JSON).content(body("[\"KYC_VERIFIED\"]", null)))
                .andExpect(status().isUnauthorized());
        assertThat(campaignRepository.count()).isZero();
    }

    // ── App : éligibilité et compatibilité ─────────────────────────────────

    @Test
    @DisplayName("nouvelle app (caps=moments) : triggers complets, triggerOptions et corridors servis, visiteur comme compte")
    void appNouvelle() throws Exception {
        String id = createActive("[\"SEARCH_TAB\",\"SEARCH_NO_TRIP_RESULTS\"]",
                "\"triggerOptions\":{\"fewResultsMax\":6},\"corridors\":[{\"from\":\"Paris\",\"to\":\"Abidjan\"},{\"to\":\"Dakar\"}]");

        for (boolean connected : new boolean[] {false, true}) {
            JsonNode list = eligible(connected, NEW_APP);
            assertThat(list).hasSize(1);
            JsonNode entry = list.get(0);
            assertThat(entry.get("id").asText()).isEqualTo(id);
            assertThat(texts(entry.get("triggers"))).containsExactly("SEARCH_TAB", "SEARCH_NO_TRIP_RESULTS");
            assertThat(entry.get("triggerOptions").get("fewResultsMax").asInt()).isEqualTo(6);
            assertThat(entry.get("triggerOptions").get("tripViewedTimes").asInt()).isEqualTo(3);
            assertThat(entry.get("corridors")).hasSize(2);
            assertThat(entry.get("corridors").get(0).get("from").asText()).isEqualTo("Paris");
            assertThat(entry.get("corridors").get(1).get("to").asText()).isEqualTo("Dakar");
        }
    }

    @Test
    @DisplayName("ancienne app : campagne mixte SEARCH_TAB + DELIVERY_CONFIRMED = SEARCH_TAB seul ; campagne uniquement moments = absente")
    void appAncienne() throws Exception {
        String mixed = createActive("[\"SEARCH_TAB\",\"DELIVERY_CONFIRMED\"]", null);
        String onlyMoments = createActive("[\"PAYMENT_FAILED\",\"APP_UPDATED\"]", null);

        for (boolean connected : new boolean[] {false, true}) {
            for (String caps : new String[] {null, "layout", OLD_APP}) {
                JsonNode list = eligible(connected, caps);
                assertThat(list).hasSize(1);
                assertThat(list.get(0).get("id").asText()).isEqualTo(mixed);
                assertThat(texts(list.get(0).get("triggers"))).containsExactly("SEARCH_TAB");
            }
            assertThat(eligible(connected, NEW_APP)).hasSize(2);
        }
        assertThat(onlyMoments).isNotEqualTo(mixed);
    }

    @Test
    @DisplayName("non-régression : une campagne d'avant (déclencheurs d'origine) est servie à toute app, triggers inchangés")
    void nonRegression() throws Exception {
        createActive("[\"APP_OPEN\",\"PUBLISH_TRIP\",\"SEND_PARCEL\",\"TRACKING_TAB\"]", null);

        for (String caps : new String[] {null, "layout", OLD_APP, NEW_APP}) {
            JsonNode list = eligible(false, caps);
            assertThat(list).hasSize(1);
            assertThat(texts(list.get(0).get("triggers")))
                    .containsExactly("APP_OPEN", "PUBLISH_TRIP", "SEND_PARCEL", "TRACKING_TAB");
        }
    }
}
