package com.yadony.api.videomodal;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.AuditService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Modals vidéo de bout en bout sur H2 (profil test) : routes de l'app (publique avec jeton
 * facultatif, et authentifiée) et back-office (ADMIN, 403, 404, 422).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Modals vidéo : /video-modals et /admin/video-modals")
class VideoModalControllersIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired VideoModalCampaignRepository campaignRepository;
    @Autowired VideoModalResponseRepository responseRepository;
    @MockitoBean AuditService auditService;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private String uid;

    private static final String VALID_BODY = """
            {"name":"Vidéo d'accueil","youtubeUrl":"https://youtu.be/dQw4w9WgXcQ","durationSeconds":204,
             "titleFr":"Bienvenue sur Yadony","titleEn":"Welcome to Yadony","subtitleFr":"Sous-titre","subtitleEn":"",
             "primaryLabelFr":"Voir la vidéo (3 min)","primaryLabelEn":"Watch the video (3 min)",
             "secondaryLabelFr":"Commencer","secondaryLabelEn":"Get started",
             "highlights":[{"icon":"PARCEL","textFr":"Envoyer un colis avec un voyageur","textEn":"Send a parcel with a traveler"}],
             "triggers":["SEARCH_TAB","SEND_PARCEL"],"maxDisplays":2,"startsAt":null,"endsAt":null}
            """;

    @BeforeEach
    void setUp() {
        // Campagnes des autres tests de la classe : une seule vit à la fois.
        responseRepository.deleteAll();
        campaignRepository.deleteAll();
        uid = "uid-video-" + UUID.randomUUID();
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
                new AdminPrincipal(ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-video"),
                null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private UsernamePasswordAuthenticationToken userAuth() {
        return new UsernamePasswordAuthenticationToken(uid, null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static UsernamePasswordAuthenticationToken guestAuth() {
        return new UsernamePasswordAuthenticationToken("uid-guest", null, List.of(new SimpleGrantedAuthority("ROLE_GUEST")));
    }

    private String createCampaign() throws Exception {
        String json = mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private String createActiveCampaign() throws Exception {
        String id = createCampaign();
        mockMvc.perform(patch("/admin/video-modals/{id}/active", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        return id;
    }

    private void sendEvent(String id, int version, String event) throws Exception {
        mockMvc.perform(post("/video-modals/{id}/events", id).with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"event\":\"" + event + "\"}"))
                .andExpect(status().isNoContent());
    }

    // ── Admin ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("création : 201, inactive, version 1, NEVER_ANSWERED, identifiant YouTube extrait, audit")
    void adminCreate() throws Exception {
        String json = mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.audience").value("NEVER_ANSWERED"))
                .andExpect(jsonPath("$.youtubeVideoId").value("dQw4w9WgXcQ"))
                .andExpect(jsonPath("$.highlights[0].icon").value("PARCEL"))
                .andExpect(jsonPath("$.triggers[1]").value("SEND_PARCEL"))
                .andExpect(jsonPath("$.subtitleEn").doesNotExist())
                .andExpect(jsonPath("$.maxDisplays").value(2))
                .andExpect(jsonPath("$.stats.shown").value(0))
                .andExpect(jsonPath("$.versionPublishedAt").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(objectMapper.readTree(json).get("id").asText());
        verify(auditService).log(eq("VIDEO_MODAL"), eq(id), eq("VIDEO_MODAL_CREATED"), eq(ADMIN_ID), any());

        // Relecture (colonnes JSON comprises).
        mockMvc.perform(get("/admin/video-modals/{id}", id).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.highlights[0].textEn").value("Send a parcel with a traveler"))
                .andExpect(jsonPath("$.triggers.length()").value(2));
    }

    @Test
    @DisplayName("non-admin : 403 sur toutes les routes admin, rien n'est créé")
    void adminForbidden() throws Exception {
        mockMvc.perform(get("/admin/video-modals").with(authentication(userAuth())))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/video-modals").with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/video-modals/{id}", UUID.randomUUID()).with(authentication(userAuth())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/video-modals")).andExpect(status().isUnauthorized());
        assertThat(campaignRepository.count()).isZero();
    }

    @Test
    @DisplayName("validation : 422 ProblemDetail pour chaque règle du contrat")
    void adminValidation() throws Exception {
        List<String> invalid = List.of(
                VALID_BODY.replace("https://youtu.be/dQw4w9WgXcQ", "https://vimeo.com/123"),
                VALID_BODY.replace("https://youtu.be/dQw4w9WgXcQ", "https://youtu.be/trop-court"),
                VALID_BODY.replace("\"triggers\":[\"SEARCH_TAB\",\"SEND_PARCEL\"]", "\"triggers\":[]"),
                VALID_BODY.replace("\"titleFr\":\"Bienvenue sur Yadony\"", "\"titleFr\":\"  \""),
                VALID_BODY.replace("\"maxDisplays\":2", "\"maxDisplays\":11"),
                VALID_BODY.replace("\"maxDisplays\":2", "\"maxDisplays\":0"),
                VALID_BODY.replace("\"startsAt\":null,\"endsAt\":null",
                        "\"startsAt\":\"2026-12-01T00:00:00Z\",\"endsAt\":\"2026-11-01T00:00:00Z\""),
                VALID_BODY.replace("\"highlights\":[", """
                        "highlights":[{"icon":"LOCK","textFr":"a"},{"icon":"LOCK","textFr":"b"},{"icon":"LOCK","textFr":"c"},"""));
        for (String body : invalid) {
            mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        }
        assertThat(campaignRepository.count()).isZero();
        verify(auditService, never()).log(any(), any(), eq("VIDEO_MODAL_CREATED"), any(), any());
    }

    @Test
    @DisplayName("campagne inconnue : 404 sur chaque route admin")
    void adminNotFound() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(get("/admin/video-modals/{id}", unknown).with(authentication(adminAuth())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(put("/admin/video-modals/{id}", unknown).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/admin/video-modals/{id}/versions", unknown).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"audience\":\"EVERYONE\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/admin/video-modals/{id}/active", unknown).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/video-modals/{id}", unknown).with(authentication(adminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("modification sans changer de version, nouvelle version, suppression douce")
    void adminLifecycle() throws Exception {
        String id = createCampaign();

        mockMvc.perform(put("/admin/video-modals/{id}", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY.replace("Bienvenue sur Yadony", "Bienvenue chez Yadony")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titleFr").value("Bienvenue chez Yadony"))
                .andExpect(jsonPath("$.version").value(1));

        mockMvc.perform(post("/admin/video-modals/{id}/versions", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"audience\":\"EVERYONE\",\"youtubeUrl\":\"https://www.youtube.com/watch?v=abcdefghijk\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.audience").value("EVERYONE"))
                .andExpect(jsonPath("$.youtubeVideoId").value("abcdefghijk"));

        mockMvc.perform(post("/admin/video-modals/{id}/versions", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch("/admin/video-modals/{id}/active", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity());

        mockMvc.perform(delete("/admin/video-modals/{id}", id).with(authentication(adminAuth())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/admin/video-modals/{id}", id).with(authentication(adminAuth())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/admin/video-modals").with(authentication(adminAuth())))
                .andExpect(jsonPath("$.length()").value(0));

        UUID uuid = UUID.fromString(id);
        verify(auditService).log(eq("VIDEO_MODAL"), eq(uuid), eq("VIDEO_MODAL_UPDATED"), eq(ADMIN_ID), any());
        verify(auditService).log(eq("VIDEO_MODAL"), eq(uuid), eq("VIDEO_MODAL_VERSION_PUBLISHED"), eq(ADMIN_ID), any());
        verify(auditService).log(eq("VIDEO_MODAL"), eq(uuid), eq("VIDEO_MODAL_DELETED"), eq(ADMIN_ID), any());
    }

    // ── App ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sans campagne active : liste vide, sans jeton")
    void eligibleEmpty() throws Exception {
        createCampaign(); // inactive par défaut
        mockMvc.perform(get("/video-modals/eligible").param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("parcours complet : visiteur, affichages, plafond, réponse, nouvelle version, stats admin")
    void eligibleFlow() throws Exception {
        String id = createActiveCampaign();

        // Visiteur sans jeton (et invité) : la campagne, displays = 0, textes EN.
        mockMvc.perform(get("/video-modals/eligible").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[0].title").value("Welcome to Yadony"))
                .andExpect(jsonPath("$[0].subtitle").value("Sous-titre"))
                .andExpect(jsonPath("$[0].highlights[0].text").value("Send a parcel with a traveler"))
                .andExpect(jsonPath("$[0].primaryLabel").value("Watch the video (3 min)"))
                .andExpect(jsonPath("$[0].secondaryLabel").value("Get started"))
                .andExpect(jsonPath("$[0].youtubeVideoId").value("dQw4w9WgXcQ"))
                .andExpect(jsonPath("$[0].durationSeconds").value(204))
                .andExpect(jsonPath("$[0].maxDisplays").value(2))
                .andExpect(jsonPath("$[0].displays").value(0))
                .andExpect(jsonPath("$[0].audience").value("NEVER_ANSWERED"))
                .andExpect(jsonPath("$[0].triggers[0]").value("SEARCH_TAB"));
        mockMvc.perform(get("/video-modals/eligible").with(authentication(guestAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Bienvenue sur Yadony"));

        // Deux affichages = plafond (maxDisplays 2) : plus proposée.
        sendEvent(id, 1, "SHOWN");
        mockMvc.perform(get("/video-modals/eligible").with(authentication(userAuth())))
                .andExpect(jsonPath("$[0].displays").value(1));
        sendEvent(id, 1, "SHOWN");
        mockMvc.perform(get("/video-modals/eligible").with(authentication(userAuth())))
                .andExpect(jsonPath("$.length()").value(0));

        // Réponse puis rejeu : idempotent, jamais rétrogradé.
        sendEvent(id, 1, "COMPLETED");
        sendEvent(id, 1, "DISMISSED");
        sendEvent(id, 1, "OPENED");
        var row = responseRepository.findAll().get(0);
        assertThat(row.getStatus()).isEqualTo(VideoModalEvent.COMPLETED);
        assertThat(row.getDisplays()).isEqualTo(2);
        assertThat(responseRepository.count()).isEqualTo(1);

        // Stats admin de la version courante.
        mockMvc.perform(get("/admin/video-modals").with(authentication(adminAuth())))
                .andExpect(jsonPath("$[0].stats.shown").value(1))
                .andExpect(jsonPath("$[0].stats.opened").value(1))
                .andExpect(jsonPath("$[0].stats.completed").value(1))
                .andExpect(jsonPath("$[0].stats.dismissed").value(0));

        // Nouvelle version pour NEVER_ANSWERED : l'utilisateur a répondu à la v1, rien.
        mockMvc.perform(post("/admin/video-modals/{id}/versions", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"audience\":\"NEVER_ANSWERED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/video-modals/eligible").with(authentication(userAuth())))
                .andExpect(jsonPath("$.length()").value(0));
        // Ancienne version : ignorée, 204.
        sendEvent(id, 1, "SHOWN");
        assertThat(responseRepository.count()).isEqualTo(1);

        // Version EVERYONE : de nouveau proposée, compteurs remis à zéro.
        mockMvc.perform(post("/admin/video-modals/{id}/versions", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"audience\":\"EVERYONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.shown").value(0));
        String json = mockMvc.perform(get("/video-modals/eligible").with(authentication(userAuth())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = objectMapper.readTree(json);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("version").asInt()).isEqualTo(3);
        assertThat(list.get(0).get("displays").asInt()).isZero();
    }

    @Test
    @DisplayName("route publique avec jeton : le vrai FirebaseTokenFilter pose l'utilisateur, la liste est filtrée")
    void eligibleWithRealBearerToken() throws Exception {
        String id = createActiveCampaign();
        sendEvent(id, 1, "DISMISSED");

        com.google.firebase.auth.FirebaseAuth firebaseAuth = org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseAuth.class);
        com.google.firebase.auth.FirebaseToken decoded = org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        org.mockito.Mockito.when(decoded.getUid()).thenReturn(uid);
        org.mockito.Mockito.when(firebaseAuth.verifyIdToken("video-token")).thenReturn(decoded);
        try (var app = org.mockito.Mockito.mockStatic(com.google.firebase.FirebaseApp.class);
             var auth = org.mockito.Mockito.mockStatic(com.google.firebase.auth.FirebaseAuth.class)) {
            app.when(com.google.firebase.FirebaseApp::getApps)
                    .thenReturn(List.of(org.mockito.Mockito.mock(com.google.firebase.FirebaseApp.class)));
            auth.when(com.google.firebase.auth.FirebaseAuth::getInstance).thenReturn(firebaseAuth);

            mockMvc.perform(get("/video-modals/eligible").header("Authorization", "Bearer video-token"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(0));
        }
        // Sans jeton, la même campagne reste proposée (l'app filtre elle-même).
        mockMvc.perform(get("/video-modals/eligible"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(id));
    }

    @Test
    @DisplayName("événements : 401 sans jeton, 403 invité, 404 campagne inconnue, 400/422 corps invalide")
    void eventErrors() throws Exception {
        String id = createActiveCampaign();
        String body = "{\"version\":1,\"event\":\"SHOWN\",\"occurredAt\":\"2026-01-10T10:00:00Z\"}";

        mockMvc.perform(post("/video-modals/{id}/events", id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/video-modals/{id}/events", id).with(authentication(guestAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/video-modals/{id}/events", UUID.randomUUID()).with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(post("/video-modals/{id}/events", id).with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"event\":\"SHOWN\"}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/video-modals/{id}/events", id).with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":1,\"event\":\"LIKED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/video-modals/{id}/events", id).with(authentication(userAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());
        // Date passée conservée (pas ramenée à maintenant). La valeur exacte est vérifiée sur
        // Postgres (VideoModalPostgresIT) : H2 décale les instants selon le fuseau de la JVM.
        assertThat(responseRepository.findAll().get(0).getLastShownAt())
                .isBefore(java.time.Instant.now().minus(java.time.Duration.ofDays(30)));
    }
}
