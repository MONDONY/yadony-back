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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Moment, ciblage et fréquence de bout en bout sur H2 (profil test) : routes admin avec tous les
 * champs, estimation d'audience, éligibilité avec et sans jeton, {@code caps=targeting},
 * {@code appVersion} et {@code platform}, DONT_SHOW_AGAIN. Les requêtes qui exigent PostgreSQL
 * (situations sur les offres, règles à {@code now()}) sont dans {@code VideoModalTargetingPostgresIT}.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Modals : moment, ciblage et fréquence (/video-modals et /admin/video-modals)")
class VideoModalTargetingIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired VideoModalCampaignRepository campaignRepository;
    @Autowired VideoModalResponseRepository responseRepository;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean AuditService auditService;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final String CAPS = "layout,targeting";
    private String uid;
    private UUID userId;

    @BeforeEach
    void setUp() {
        responseRepository.deleteAll();
        campaignRepository.deleteAll();
        uid = "uid-targeting-" + UUID.randomUUID();
        userId = persistUser(uid, AppLanguage.FR, "TD").getId();
    }

    private UserEntity persistUser(String firebaseUid, AppLanguage language, String country) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(firebaseUid);
        u.setFirstName("Awa");
        u.setLastName("Koné");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setTotalTrips(0);
        u.setPreferredLanguage(language);
        u.setCountry(country);
        return userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-targeting"),
                null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private UsernamePasswordAuthenticationToken userAuth() {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    /** Corps d'une vidéo valide, avec les champs de ciblage donnés (fragment JSON sans accolades) en plus. */
    private static String body(String extra) {
        return """
                {"name":"Campagne","youtubeUrl":"https://youtu.be/dQw4w9WgXcQ","durationSeconds":204,
                 "titleFr":"Bienvenue","titleEn":"Welcome","highlights":[],
                 "triggers":["SEARCH_TAB"],"maxDisplays":3%s}
                """.formatted(extra == null || extra.isBlank() ? "" : "," + extra);
    }

    private JsonNode create(String extra) throws Exception {
        String json = mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(extra)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private String createActive(String extra) throws Exception {
        String id = create(extra).get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/admin/video-modals/{id}/active", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        return id;
    }

    private void sendEvent(String id, int version, String event) throws Exception {
        mockMvc.perform(post("/video-modals/{id}/events", id).with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"event\":\"" + event + "\"}"))
                .andExpect(status().isNoContent());
    }

    private JsonNode eligible(boolean connected, String... params) throws Exception {
        var request = get("/video-modals/eligible");
        if (connected) request = request.with(authentication(userAuth()));
        for (int i = 0; i < params.length; i += 2) request = request.param(params[i], params[i + 1]);
        String json = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private static List<String> idsOf(JsonNode list) {
        List<String> out = new java.util.ArrayList<>();
        list.forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    // ── Admin ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("création avec tous les champs : 201, relecture, modification, audit")
    void adminTousLesChamps() throws Exception {
        String extra = """
                "frequency":"EVERY_N_DAYS","frequencyDays":7,"stopAfterAnswer":false,"allowDontShowAgain":true,
                "priority":4,"connectedOnly":true,
                "conditions":{"op":"AND","children":[{"field":"language","operator":"IN","value":["fr"]},
                              {"field":"completed_trips","operator":"GTE","value":1}]},
                "minAppVersion":"1.0.6","maxAppVersion":"2.0.0","platforms":["IOS","ANDROID"],"samplePercent":40,
                "situations":[{"kind":"OFFER_UNANSWERED","value":24},{"kind":"PROFILE_INCOMPLETE"}]
                """;
        JsonNode created = create(extra);

        assertThat(created.get("frequency").asText()).isEqualTo("EVERY_N_DAYS");
        assertThat(created.get("frequencyDays").asInt()).isEqualTo(7);
        assertThat(created.get("stopAfterAnswer").asBoolean()).isFalse();
        assertThat(created.get("allowDontShowAgain").asBoolean()).isTrue();
        assertThat(created.get("priority").asInt()).isEqualTo(4);
        assertThat(created.get("connectedOnly").asBoolean()).isTrue();
        assertThat(created.get("conditions").get("op").asText()).isEqualTo("AND");
        assertThat(created.get("conditions").get("children")).hasSize(2);
        assertThat(created.get("minAppVersion").asText()).isEqualTo("1.0.6");
        assertThat(created.get("maxAppVersion").asText()).isEqualTo("2.0.0");
        assertThat(created.get("platforms")).extracting(JsonNode::asText).containsExactly("IOS", "ANDROID");
        assertThat(created.get("samplePercent").asInt()).isEqualTo(40);
        assertThat(created.get("situations").get(0).get("kind").asText()).isEqualTo("OFFER_UNANSWERED");
        assertThat(created.get("situations").get(0).get("value").asInt()).isEqualTo(24);
        assertThat(created.get("situations").get(1).path("value").isMissingNode()).isTrue(); // valeur nulle : champ omis
        assertThat(created.get("stats").get("dontShowAgain").asInt()).isZero();

        UUID id = UUID.fromString(created.get("id").asText());
        verify(auditService).log(eq("VIDEO_MODAL"), eq(id), eq("VIDEO_MODAL_CREATED"), eq(ADMIN_ID), any());

        // Relecture (colonnes JSON et conditions comprises).
        mockMvc.perform(get("/admin/video-modals/{id}", id).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conditions.children.length()").value(2))
                .andExpect(jsonPath("$.platforms[1]").value("ANDROID"))
                .andExpect(jsonPath("$.situations.length()").value(2));

        // Modification : retour aux défauts quand les champs sont absents.
        mockMvc.perform(put("/admin/video-modals/{id}", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.frequency").value("UP_TO_N"))
                .andExpect(jsonPath("$.stopAfterAnswer").value(true))
                .andExpect(jsonPath("$.conditions").doesNotExist()) // aucune condition : null (champ omis)
                .andExpect(jsonPath("$.situations.length()").value(0))
                .andExpect(jsonPath("$.samplePercent").value(100));
        verify(auditService).log(eq("VIDEO_MODAL"), eq(id), eq("VIDEO_MODAL_UPDATED"), eq(ADMIN_ID), any());
    }

    @Test
    @DisplayName("sans champ nouveau : l'ancien corps est toujours accepté avec les défauts d'avant")
    void ancienCorps() throws Exception {
        JsonNode created = create(null);
        assertThat(created.get("frequency").asText()).isEqualTo("UP_TO_N");
        assertThat(created.get("stopAfterAnswer").asBoolean()).isTrue();
        assertThat(created.get("allowDontShowAgain").asBoolean()).isFalse();
        assertThat(created.get("priority").asInt()).isZero();
        assertThat(created.get("connectedOnly").asBoolean()).isFalse();
        assertThat(created.get("samplePercent").asInt()).isEqualTo(100);
        assertThat(created.get("platforms")).isEmpty();
        assertThat(created.get("situations")).isEmpty();
        assertThat(created.hasNonNull("conditions")).isFalse();
    }

    @Test
    @DisplayName("non-admin : 403 sur la création avec ciblage et sur l'estimation, 401 sans jeton")
    void adminForbidden() throws Exception {
        mockMvc.perform(post("/admin/video-modals").with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body("\"priority\":9")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/video-modals/audience-estimate").with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/video-modals/audience-estimate")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        assertThat(campaignRepository.count()).isZero();
    }

    @Test
    @DisplayName("validation : 422 ProblemDetail pour chaque règle du contrat (fréquence, versions, tirage, situations, conditions)")
    void adminValidation() throws Exception {
        List<String> invalid = List.of(
                "\"frequency\":\"EVERY_N_DAYS\"",
                "\"frequency\":\"EVERY_N_DAYS\",\"frequencyDays\":0",
                "\"frequency\":\"EVERY_N_DAYS\",\"frequencyDays\":366",
                "\"minAppVersion\":\"1.0\"",
                "\"maxAppVersion\":\"un\"",
                "\"minAppVersion\":\"2.0.0\",\"maxAppVersion\":\"1.0.0\"",
                "\"samplePercent\":0",
                "\"samplePercent\":101",
                "\"priority\":1001",
                "\"priority\":-1001",
                "\"situations\":[{\"kind\":\"INCONNUE\",\"value\":1}]",
                "\"situations\":[{\"kind\":\"OFFER_UNANSWERED\",\"value\":721}]",
                "\"situations\":[{\"kind\":\"OFFER_UNANSWERED\",\"value\":0}]",
                "\"situations\":[{\"kind\":\"RETURN_AFTER_ABSENCE\",\"value\":366}]",
                "\"conditions\":{\"op\":\"AND\"}",
                "\"conditions\":{\"op\":\"AND\",\"children\":[{\"field\":\"inconnu\",\"operator\":\"IS\",\"value\":true}]}",
                "\"conditions\":{\"op\":\"AND\",\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"klingon\"]}]}");
        for (String extra : invalid) {
            mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                            .contentType(MediaType.APPLICATION_JSON).content(body(extra)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        // Sans déclencheur ni situation : 422 ; avec une situation, accepté.
        String noTrigger = body("\"x\":1").replace("\"triggers\":[\"SEARCH_TAB\"]", "\"triggers\":[]").replace(",\"x\":1", "");
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(noTrigger))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(noTrigger.replace("\"maxDisplays\":3", "\"maxDisplays\":3,\"situations\":[{\"kind\":\"PROFILE_INCOMPLETE\"}]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.triggers.length()").value(0));
        assertThat(campaignRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("énumérations inconnues (fréquence, système) : refusées par la désérialisation, comme les autres enums")
    void enumInconnue() throws Exception {
        for (String extra : List.of("\"frequency\":\"SOMETIMES\"", "\"platforms\":[\"WINDOWS\"]")) {
            mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                            .contentType(MediaType.APPLICATION_JSON).content(body(extra)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ── Estimation d'audience ──────────────────────────────────────────────

    @Test
    @DisplayName("audience-estimate : comptes concernés par les conditions puis après tirage, sans aucune donnée personnelle")
    void audienceEstimate() throws Exception {
        persistUser("uid-td-2-" + UUID.randomUUID(), AppLanguage.EN, "TD");
        persistUser("uid-td-3-" + UUID.randomUUID(), AppLanguage.FR, "TD");
        long chad = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE upper(country) = 'TD' AND status = 'ACTIVE' AND deleted_at IS NULL", Long.class);
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE status = 'ACTIVE' AND deleted_at IS NULL", Long.class);
        assertThat(chad).isGreaterThanOrEqualTo(3);

        String response = mockMvc.perform(post("/admin/video-modals/audience-estimate").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conditions":{"op":"AND","children":[{"field":"country","operator":"IN","value":["TD"]}]},
                                 "connectedOnly":true,"samplePercent":50,"minAppVersion":"1.0.6","maxAppVersion":"2.0.0"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").value(chad))
                .andExpect(jsonPath("$.afterSample").value(Math.round(chad * 0.5)))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(response).fieldNames()).toIterable().containsExactlyInAnyOrder("accounts", "afterSample");

        // Sans condition : tous les comptes actifs ; tirage par défaut 100.
        mockMvc.perform(post("/admin/video-modals/audience-estimate").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").value(total))
                .andExpect(jsonPath("$.afterSample").value(total));
        // Un système : seuls les comptes qui ont un appareil de ce système (aucun ici).
        mockMvc.perform(post("/admin/video-modals/audience-estimate").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conditions\":{\"op\":\"AND\",\"children\":[{\"field\":\"country\",\"operator\":\"IN\",\"value\":[\"TD\"]}]},\"platforms\":[\"IOS\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").value(0));
        verify(auditService, never()).log(any(), any(), eq("VIDEO_MODAL_CREATED"), any(), any());
    }

    @Test
    @DisplayName("audience-estimate : 422 pour conditions, tirage ou version invalides")
    void audienceEstimateInvalide() throws Exception {
        for (String bad : List.of(
                "{\"conditions\":{\"op\":\"AND\",\"children\":[{\"field\":\"inconnu\",\"operator\":\"IS\",\"value\":true}]}}",
                "{\"conditions\":{\"op\":\"AND\"}}",
                "{\"samplePercent\":0}", "{\"samplePercent\":101}", "{\"minAppVersion\":\"1.0\"}")) {
            mockMvc.perform(post("/admin/video-modals/audience-estimate").with(authentication(adminAuth()))
                            .contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
    }

    // ── App : éligibilité ──────────────────────────────────────────────────

    @Test
    @DisplayName("conditions de la Diffusion : seul le compte concerné reçoit la campagne ; le visiteur ne reçoit que les campagnes sans condition")
    void eligibleConditions() throws Exception {
        String free = createActive(null);
        String fr = createActive("\"conditions\":{\"op\":\"AND\",\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"fr\"]}]}");
        String en = createActive("\"conditions\":{\"op\":\"AND\",\"children\":[{\"field\":\"language\",\"operator\":\"IN\",\"value\":[\"en\"]}]}");
        String sample0 = createActive("\"samplePercent\":100,\"connectedOnly\":true");

        assertThat(idsOf(eligible(true, "caps", CAPS))).containsExactlyInAnyOrder(free, fr, sample0);
        assertThat(idsOf(eligible(true, "caps", CAPS))).doesNotContain(en);
        // Visiteur : jamais de campagne à conditions ni connectedOnly.
        assertThat(idsOf(eligible(false, "caps", CAPS))).containsExactly(free);
        assertThat(idsOf(eligible(false))).containsExactly(free);
    }

    @Test
    @DisplayName("tirage stable : même résultat à chaque appel pour le même compte ; 100 % pour tous")
    void eligibleTirage() throws Exception {
        String id = createActive("\"samplePercent\":50");
        boolean inSample = VideoModalTargeting.inSample(userId, UUID.fromString(id), 50);
        for (int i = 0; i < 20; i++) {
            assertThat(idsOf(eligible(true)).contains(id)).isEqualTo(inSample);
        }
        assertThat(eligible(false)).isEmpty(); // tirage < 100 : jamais pour un visiteur
    }

    @Test
    @DisplayName("situation PROFILE_INCOMPLETE : servie avec APP_OPEN tant que le profil est incomplet, plus après")
    void eligibleSituationProfil() throws Exception {
        String body = body("\"situations\":[{\"kind\":\"PROFILE_INCOMPLETE\"}]")
                .replace("\"triggers\":[\"SEARCH_TAB\"]", "\"triggers\":[]");
        String id = objectMapper.readTree(mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/admin/video-modals/{id}/active", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());

        JsonNode list = eligible(true);
        assertThat(idsOf(list)).containsExactly(id);
        assertThat(list.get(0).get("triggers")).extracting(JsonNode::asText).containsExactly("APP_OPEN");
        assertThat(eligible(false)).isEmpty(); // une situation suppose un compte

        UserEntity u = userRepository.findById(userId).orElseThrow();
        u.setAvatarUrl("https://cdn.yadony.test/a.jpg");
        u.setCity("Paris");
        u.setBio("Voyageur régulier");
        userRepository.save(u);
        assertThat(eligible(true)).isEmpty();

        u = userRepository.findById(userId).orElseThrow();
        u.setBio("   ");
        userRepository.save(u);
        assertThat(idsOf(eligible(true))).containsExactly(id);
    }

    @Test
    @DisplayName("priorité : décroissante, puis version publiée la plus récente ; champs de fréquence dans la réponse")
    void eligiblePrioriteEtChamps() throws Exception {
        String low = createActive(null);
        String high = createActive("\"priority\":10,\"frequency\":\"EVERY_OPEN\",\"stopAfterAnswer\":false,\"allowDontShowAgain\":true");
        String mid = createActive("\"priority\":3,\"frequency\":\"EVERY_N_DAYS\",\"frequencyDays\":2");

        JsonNode list = eligible(true, "caps", CAPS);

        assertThat(idsOf(list)).containsExactly(high, mid, low);
        assertThat(list.get(0).get("priority").asInt()).isEqualTo(10);
        assertThat(list.get(0).get("frequency").asText()).isEqualTo("EVERY_OPEN");
        assertThat(list.get(0).get("stopAfterAnswer").asBoolean()).isFalse();
        assertThat(list.get(0).get("allowDontShowAgain").asBoolean()).isTrue();
        assertThat(list.get(0).has("frequencyDays")).isFalse(); // nul : champ omis par l'API
        assertThat(list.get(0).has("lastShownAt")).isFalse();
        assertThat(list.get(1).get("frequencyDays").asInt()).isEqualTo(2);
        assertThat(list.get(2).get("frequency").asText()).isEqualTo("UP_TO_N");
    }

    @Test
    @DisplayName("compatibilité : sans caps=targeting, EVERY_OPEN, EVERY_N_DAYS et « ne plus afficher » ne sont pas envoyés")
    void eligibleSansCapsTargeting() throws Exception {
        String ordinary = createActive(null);
        createActive("\"frequency\":\"EVERY_OPEN\"");
        createActive("\"frequency\":\"EVERY_N_DAYS\",\"frequencyDays\":3");
        createActive("\"allowDontShowAgain\":true");
        String once = createActive("\"frequency\":\"ONCE\"");

        assertThat(idsOf(eligible(true))).containsExactlyInAnyOrder(ordinary, once);
        assertThat(idsOf(eligible(true, "caps", "layout"))).containsExactlyInAnyOrder(ordinary, once);
        assertThat(idsOf(eligible(false))).containsExactlyInAnyOrder(ordinary, once);
        assertThat(eligible(true, "caps", CAPS)).hasSize(5);
        assertThat(eligible(false, "caps", "targeting")).hasSize(5);
    }

    @Test
    @DisplayName("version et système : appVersion et platform filtrent ; inconnus = campagnes restreintes non envoyées")
    void eligibleVersionEtPlateforme() throws Exception {
        String open = createActive(null);
        String recent = createActive("\"minAppVersion\":\"1.0.6\"");
        String legacy = createActive("\"maxAppVersion\":\"1.0.5\"");
        String ios = createActive("\"platforms\":[\"IOS\"]");

        assertThat(idsOf(eligible(true, "appVersion", "1.0.6", "platform", "ios"))).containsExactlyInAnyOrder(open, recent, ios);
        assertThat(idsOf(eligible(true, "appVersion", "1.0.5", "platform", "android"))).containsExactlyInAnyOrder(open, legacy);
        assertThat(idsOf(eligible(false, "appVersion", "1.0.6+1053", "platform", "IOS"))).containsExactlyInAnyOrder(open, recent, ios);
        assertThat(idsOf(eligible(true))).containsExactly(open);
        assertThat(idsOf(eligible(true, "appVersion", "bof", "platform", "web"))).containsExactly(open);
    }

    // ── DONT_SHOW_AGAIN et fréquence ───────────────────────────────────────

    @Test
    @DisplayName("DONT_SHOW_AGAIN : 204, la campagne disparaît quelles que soient fréquence et réponse, statistique admin")
    void dontShowAgain() throws Exception {
        String id = createActive("\"frequency\":\"EVERY_OPEN\",\"stopAfterAnswer\":false,\"allowDontShowAgain\":true");
        sendEvent(id, 1, "SHOWN");
        sendEvent(id, 1, "OPENED");
        // EVERY_OPEN sans arrêt après réponse : toujours proposée, avec son dernier affichage.
        JsonNode before = eligible(true, "caps", CAPS);
        assertThat(idsOf(before)).containsExactly(id);
        assertThat(before.get(0).hasNonNull("lastShownAt")).isTrue();
        assertThat(before.get(0).get("displays").asInt()).isEqualTo(1);

        sendEvent(id, 1, "DONT_SHOW_AGAIN");
        sendEvent(id, 1, "DONT_SHOW_AGAIN"); // rejoué : idempotent
        assertThat(eligible(true, "caps", CAPS)).isEmpty();

        var row = responseRepository.findAll().get(0);
        assertThat(row.isDontShowAgain()).isTrue();
        assertThat(row.getStatus()).isEqualTo(VideoModalEvent.OPENED);
        assertThat(row.getDisplays()).isEqualTo(1);
        mockMvc.perform(get("/admin/video-modals").with(authentication(adminAuth())))
                .andExpect(jsonPath("$[0].stats.dontShowAgain").value(1))
                .andExpect(jsonPath("$[0].stats.shown").value(1))
                .andExpect(jsonPath("$[0].stats.opened").value(1));
    }

    @Test
    @DisplayName("DONT_SHOW_AGAIN est propre à la version : une nouvelle version est de nouveau proposée")
    void dontShowAgainParVersion() throws Exception {
        String id = createActive("\"allowDontShowAgain\":true");
        sendEvent(id, 1, "SHOWN");
        sendEvent(id, 1, "DONT_SHOW_AGAIN");
        assertThat(eligible(true, "caps", CAPS)).isEmpty();

        mockMvc.perform(post("/admin/video-modals/{id}/versions", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"audience\":\"EVERYONE\"}"))
                .andExpect(status().isOk());
        assertThat(idsOf(eligible(true, "caps", CAPS))).containsExactly(id);
    }

    @Test
    @DisplayName("EVERY_N_DAYS : proposée jusqu'au premier affichage, puis plus avant N jours")
    void everyNDays() throws Exception {
        String id = createActive("\"frequency\":\"EVERY_N_DAYS\",\"frequencyDays\":3,\"stopAfterAnswer\":false");
        assertThat(idsOf(eligible(true, "caps", CAPS))).containsExactly(id);
        sendEvent(id, 1, "SHOWN");
        assertThat(eligible(true, "caps", CAPS)).isEmpty();
        // Un affichage vieux de 4 jours (rejoué avec sa date) rend de nouveau la campagne éligible : la
        // marge absorbe le décalage de fuseau de H2 ; l'exactitude à la seconde est testée en unitaire.
        responseRepository.deleteAll();
        String threeDaysAgo = java.time.Instant.now().minus(java.time.Duration.ofDays(4)).toString();
        mockMvc.perform(post("/video-modals/{id}/events", id).with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"event\":\"SHOWN\",\"occurredAt\":\"" + threeDaysAgo + "\"}"))
                .andExpect(status().isNoContent());
        assertThat(idsOf(eligible(true, "caps", CAPS))).containsExactly(id);
    }

    @Test
    @DisplayName("non-régression : campagne créée à l'ancienne, mêmes réponses qu'avant (plafond, réponse)")
    void nonRegression() throws Exception {
        String id = createActive(null);

        assertThat(idsOf(eligible(false))).containsExactly(id);
        for (int i = 0; i < 3; i++) sendEvent(id, 1, "SHOWN");
        assertThat(eligible(true)).isEmpty(); // plafond maxDisplays = 3
        responseRepository.deleteAll();
        sendEvent(id, 1, "DISMISSED");
        assertThat(eligible(true)).isEmpty(); // une réponse arrête l'affichage
        assertThat(idsOf(eligible(false))).containsExactly(id); // le visiteur : l'app applique ses règles
    }

    @Test
    @DisplayName("contrat de l'admin : audience à la création, conditions null ou vides, ONCE, situations à valeur nulle, priorité")
    void contratDeLAdmin() throws Exception {
        String info = """
                {"name":"Info","type":"INFO","titleFr":"Titre","bodyFr":"Texte","icon":"INFO",
                 "primaryAction":{"kind":"CLOSE"},"triggers":[],"maxDisplays":1,
                 "frequency":"ONCE","frequencyDays":null,"conditions":null,"priority":-1000,
                 "situations":[{"kind":"PROFILE_INCOMPLETE","value":null},{"kind":"OFFER_UNANSWERED","value":null}]%s}
                """;
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(info.formatted("")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.audience").value("EVERYONE"))
                .andExpect(jsonPath("$.frequency").value("ONCE"))
                .andExpect(jsonPath("$.maxDisplays").value(1))
                .andExpect(jsonPath("$.frequencyDays").doesNotExist())
                .andExpect(jsonPath("$.conditions").doesNotExist())
                .andExpect(jsonPath("$.priority").value(-1000))
                .andExpect(jsonPath("$.triggers.length()").value(0))
                .andExpect(jsonPath("$.situations[0].kind").value("PROFILE_INCOMPLETE"))
                .andExpect(jsonPath("$.situations[1].value").value(24));
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(info.formatted(",\"audience\":\"NOT_WATCHED_PREVIOUS\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.audience").value("NOT_WATCHED_PREVIOUS"));
        // Vidéo sans audience : NEVER_ANSWERED, comme avant ; groupe vide accepté comme null.
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("\"conditions\":{\"op\":\"AND\",\"children\":[]}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.audience").value("NEVER_ANSWERED"))
                .andExpect(jsonPath("$.conditions").doesNotExist());
        // Estimation : conditions null ou groupe vide = tous les comptes.
        for (String estimate : List.of("{\"conditions\":null}", "{\"conditions\":{\"op\":\"AND\",\"children\":[]}}")) {
            mockMvc.perform(post("/admin/video-modals/audience-estimate").with(authentication(adminAuth()))
                            .contentType(MediaType.APPLICATION_JSON).content(estimate))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accounts").isNumber());
        }
    }
}
