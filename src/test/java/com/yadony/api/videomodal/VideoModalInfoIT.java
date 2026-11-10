package com.yadony.api.videomodal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
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

/** Modals INFO de bout en bout sur H2 : admin (création, modification, version, 403, 422) et app. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Modals INFO : /video-modals et /admin/video-modals")
class VideoModalInfoIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired VideoModalCampaignRepository campaignRepository;
    @Autowired VideoModalResponseRepository responseRepository;
    @MockitoBean AuditService auditService;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private String uid;

    private static final String INFO_BODY = """
            {"type":"INFO","name":"Info colis","titleFr":"Votre colis arrive","titleEn":"Your parcel is coming",
             "subtitleFr":"Bonne nouvelle","subtitleEn":"",
             "bodyFr":"Suivez-le en direct.","bodyEn":"Follow it live.","icon":"BELL",
             "primaryLabelFr":"Suivre","primaryLabelEn":"Track",
             "primaryAction":{"kind":"SCREEN","screen":"TRACKING","url":null},
             "secondaryLabelFr":"Plus tard","secondaryLabelEn":"Later",
             "secondaryAction":{"kind":"CLOSE"},
             "triggers":["APP_OPEN"],"maxDisplays":2,"startsAt":null,"endsAt":null}
            """;

    private static final String VIDEO_BODY = """
            {"name":"Vidéo d'accueil","youtubeUrl":"https://youtu.be/dQw4w9WgXcQ","durationSeconds":204,
             "titleFr":"Bienvenue","triggers":["SEARCH_TAB"],"maxDisplays":2}
            """;

    @BeforeEach
    void setUp() {
        responseRepository.deleteAll();
        campaignRepository.deleteAll();
        uid = "uid-info-" + UUID.randomUUID();
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        u.setFirstName("Awa");
        u.setLastName("Koné");
        u.setStatus(UserStatus.ACTIVE);
        u.setKycStatus(KycStatus.NOT_STARTED);
        u.setTotalTrips(0);
        userRepository.save(u);
    }

    private static UsernamePasswordAuthenticationToken adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-info"),
                null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("NOTIFICATION_SEND")));
    }

    private UsernamePasswordAuthenticationToken userAuth() {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private String create(String body) throws Exception {
        String json = mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private void activate(String id) throws Exception {
        mockMvc.perform(patch("/admin/video-modals/{id}/active", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
    }

    private void sendEvent(String id, int version, String event) throws Exception {
        mockMvc.perform(post("/video-modals/{id}/events", id).with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"event\":\"" + event + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("création INFO : 201, champs INFO relus, pas de vidéo, audit")
    void createInfo() throws Exception {
        String json = mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(INFO_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("INFO"))
                .andExpect(jsonPath("$.bodyFr").value("Suivez-le en direct."))
                .andExpect(jsonPath("$.bodyEn").value("Follow it live."))
                .andExpect(jsonPath("$.icon").value("BELL"))
                .andExpect(jsonPath("$.primaryAction.kind").value("SCREEN"))
                .andExpect(jsonPath("$.primaryAction.screen").value("TRACKING"))
                .andExpect(jsonPath("$.secondaryAction.kind").value("CLOSE"))
                .andExpect(jsonPath("$.youtubeVideoId").doesNotExist())
                .andExpect(jsonPath("$.youtubeUrl").doesNotExist())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(1))
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(json).get("id").asText());
        verify(auditService).log(eq("VIDEO_MODAL"), eq(id), eq("VIDEO_MODAL_CREATED"), eq(ADMIN_ID), any());

        mockMvc.perform(get("/admin/video-modals/{id}", id).with(authentication(adminAuth())))
                .andExpect(jsonPath("$.primaryAction.screen").value("TRACKING"))
                .andExpect(jsonPath("$.primaryAction.url").doesNotExist());
        mockMvc.perform(get("/admin/video-modals").with(authentication(adminAuth())))
                .andExpect(jsonPath("$[0].type").value("INFO"));
    }

    @Test
    @DisplayName("modification, nouvelle version et statistiques d'un INFO (bouton touché, jamais « vue en entier »)")
    void lifecycleInfo() throws Exception {
        String id = create(INFO_BODY);

        mockMvc.perform(put("/admin/video-modals/{id}", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INFO_BODY.replace("{\"kind\":\"SCREEN\",\"screen\":\"TRACKING\",\"url\":null}",
                                "{\"kind\":\"URL\",\"url\":\"https://yadony.com/aide\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryAction.kind").value("URL"))
                .andExpect(jsonPath("$.primaryAction.url").value("https://yadony.com/aide"))
                .andExpect(jsonPath("$.primaryAction.screen").doesNotExist())
                .andExpect(jsonPath("$.version").value(1));

        activate(id);
        sendEvent(id, 1, "SHOWN");
        sendEvent(id, 1, "OPENED");
        sendEvent(id, 1, "COMPLETED");
        mockMvc.perform(get("/admin/video-modals/{id}", id).with(authentication(adminAuth())))
                .andExpect(jsonPath("$.stats.shown").value(1))
                .andExpect(jsonPath("$.stats.opened").value(1))
                .andExpect(jsonPath("$.stats.completed").value(0))
                .andExpect(jsonPath("$.stats.dismissed").value(0));

        mockMvc.perform(post("/admin/video-modals/{id}/versions", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"audience\":\"EVERYONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.type").value("INFO"))
                .andExpect(jsonPath("$.audience").value("EVERYONE"))
                .andExpect(jsonPath("$.youtubeVideoId").doesNotExist());
    }

    @Test
    @DisplayName("non-admin : 403 pour créer un INFO, rien n'est créé")
    void forbidden() throws Exception {
        mockMvc.perform(post("/admin/video-modals").with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(INFO_BODY))
                .andExpect(status().isForbidden());
        assertThat(campaignRepository.count()).isZero();
    }

    @Test
    @DisplayName("validation INFO : 422 ProblemDetail pour chaque règle du contrat")
    void validation() throws Exception {
        List<String> invalid = List.of(
                INFO_BODY.replaceAll("\"primaryAction\":\\{[^}]*\\},", ""),
                INFO_BODY.replace("\"bodyFr\":\"Suivez-le en direct.\"", "\"bodyFr\":\"  \""),
                INFO_BODY.replace("\"screen\":\"TRACKING\"", "\"screen\":\"ADMIN\""),
                INFO_BODY.replace("\"screen\":\"TRACKING\"", "\"screen\":null"),
                INFO_BODY.replace("\"kind\":\"SCREEN\"", "\"kind\":\"TELEPORT\""),
                INFO_BODY.replace("{\"kind\":\"SCREEN\",\"screen\":\"TRACKING\",\"url\":null}",
                        "{\"kind\":\"URL\",\"url\":\"http://yadony.com\"}"),
                INFO_BODY.replace("{\"kind\":\"SCREEN\",\"screen\":\"TRACKING\",\"url\":null}",
                        "{\"kind\":\"URL\",\"url\":\"javascript:alert(1)\"}"),
                INFO_BODY.replace("{\"kind\":\"SCREEN\",\"screen\":\"TRACKING\",\"url\":null}",
                        "{\"kind\":\"URL\",\"url\":\"data:text/html,x\"}"),
                INFO_BODY.replace("{\"kind\":\"SCREEN\",\"screen\":\"TRACKING\",\"url\":null}",
                        "{\"kind\":\"URL\",\"url\":\"mailto:a@b.fr\"}"),
                INFO_BODY.replace("{\"kind\":\"SCREEN\",\"screen\":\"TRACKING\",\"url\":null}",
                        "{\"kind\":\"URL\",\"url\":\"https://yadony.com/" + "a".repeat(2000) + "\"}"),
                INFO_BODY.replace("\"primaryLabelFr\":\"Suivre\"", "\"primaryLabelFr\":\"\""),
                INFO_BODY.replace("\"primaryLabelFr\":\"Suivre\"", "\"primaryLabelFr\":\"" + "x".repeat(27) + "\""));
        for (String body : invalid) {
            mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        assertThat(campaignRepository.count()).isZero();
    }

    @Test
    @DisplayName("VIDEO sans type dans le corps : reste une VIDEO ; sans lien YouTube : 422")
    void videoWithoutType() throws Exception {
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(VIDEO_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("VIDEO"))
                .andExpect(jsonPath("$.youtubeVideoId").value("dQw4w9WgXcQ"))
                .andExpect(jsonPath("$.bodyFr").doesNotExist());
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VIDEO_BODY.replace("\"youtubeUrl\":\"https://youtu.be/dQw4w9WgXcQ\",", "")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    @DisplayName("eligible : INFO avec ses champs, VIDEO inchangée avec type VIDEO, ordre par version publiée")
    void eligibleMixed() throws Exception {
        String video = create(VIDEO_BODY);
        activate(video);
        String info = create(INFO_BODY);
        activate(info);

        // L'INFO a été publié en dernier : il passe d'abord.
        mockMvc.perform(get("/video-modals/eligible").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(info))
                .andExpect(jsonPath("$[0].type").value("INFO"))
                .andExpect(jsonPath("$[0].title").value("Your parcel is coming"))
                .andExpect(jsonPath("$[0].subtitle").value("Bonne nouvelle"))
                .andExpect(jsonPath("$[0].body").value("Follow it live."))
                .andExpect(jsonPath("$[0].icon").value("BELL"))
                .andExpect(jsonPath("$[0].primaryLabel").value("Track"))
                .andExpect(jsonPath("$[0].primaryAction.kind").value("SCREEN"))
                .andExpect(jsonPath("$[0].primaryAction.screen").value("TRACKING"))
                .andExpect(jsonPath("$[0].secondaryLabel").value("Later"))
                .andExpect(jsonPath("$[0].secondaryAction.kind").value("CLOSE"))
                .andExpect(jsonPath("$[0].youtubeVideoId").doesNotExist())
                .andExpect(jsonPath("$[0].durationSeconds").doesNotExist())
                .andExpect(jsonPath("$[0].maxDisplays").value(2))
                .andExpect(jsonPath("$[0].displays").value(0))
                // Une information créée sans « audience » vaut « tout le monde » (étape 1 : défaut voulu du type).
                .andExpect(jsonPath("$[0].audience").value("EVERYONE"))
                .andExpect(jsonPath("$[0].triggers[0]").value("APP_OPEN"))
                .andExpect(jsonPath("$[1].id").value(video))
                .andExpect(jsonPath("$[1].type").value("VIDEO"))
                .andExpect(jsonPath("$[1].youtubeVideoId").value("dQw4w9WgXcQ"))
                .andExpect(jsonPath("$[1].durationSeconds").value(204))
                .andExpect(jsonPath("$[1].primaryLabel").value("Watch the video (3 min)"))
                .andExpect(jsonPath("$[1].secondaryLabel").value("Get started"))
                .andExpect(jsonPath("$[1].body").doesNotExist())
                .andExpect(jsonPath("$[1].primaryAction").doesNotExist());
    }

    @Test
    @DisplayName("eligible avec jeton : un INFO touché (OPENED) disparaît de la liste de l'utilisateur")
    void eligibleFiltersAnsweredInfo() throws Exception {
        String info = create(INFO_BODY);
        activate(info);
        sendEvent(info, 1, "SHOWN");
        mockMvc.perform(get("/video-modals/eligible").with(authentication(userAuth())))
                .andExpect(jsonPath("$[0].displays").value(1));
        sendEvent(info, 1, "OPENED");
        mockMvc.perform(get("/video-modals/eligible").with(authentication(userAuth())))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
