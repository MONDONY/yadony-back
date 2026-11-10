package com.yadony.api.videomodal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.yadony.api.videomodal.VideoModalImageTestFiles.image;
import static com.yadony.api.videomodal.VideoModalImageTestFiles.jpegHeaderOnly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Format, taille et image de bout en bout sur H2 : envoi multipart d'une image (valide, type annoncé
 * faux, trop lourde, trop grande), retrait, droits admin, et filtre {@code caps=layout} de l'app.
 * Le stockage R2 est simulé ; la validation de type et d'octets de tête reste celle du vrai service.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("Modals : format, taille et image (/video-modals et /admin/video-modals)")
class VideoModalFormatIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired VideoModalCampaignRepository campaignRepository;
    @Autowired VideoModalResponseRepository responseRepository;
    @MockitoBean AuditService auditService;
    @MockitoSpyBean StorageService storageService;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    private static final String INFO_BODY = """
            {"type":"INFO","name":"Concours","titleFr":"Grand concours","titleEn":"Big contest",
             "bodyFr":"Gagnez un lot.","bodyEn":"Win a prize.","icon":"STAR",
             "primaryLabelFr":"Participer","primaryLabelEn":"Join",
             "primaryAction":{"kind":"URL","url":"https://yadony.com/concours"},
             "triggers":["APP_OPEN"],"maxDisplays":2,
             "layout":"FULLSCREEN","sizePercent":80,"imageMode":"NONE","imageFit":"CONTAIN",
             "imageAltFr":"Affiche du concours","imageAltEn":"Contest poster"}
            """;

    @BeforeEach
    void setUp() {
        responseRepository.deleteAll();
        campaignRepository.deleteAll();
        doReturn("video-modals/k/1700000000000.png").when(storageService).storeAsset(any(), anyString(), anyString(), any());
        doNothing().when(storageService).deleteQuietly(anyString());
        doReturn("https://r2.test/signed").when(storageService).generatePresignedUrl(anyString(), any(Duration.class));
    }

    private static UsernamePasswordAuthenticationToken adminAuth() {
        return new UsernamePasswordAuthenticationToken(
                new AdminPrincipal(ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-format"),
                null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static UsernamePasswordAuthenticationToken userAuth() {
        return new UsernamePasswordAuthenticationToken("uid-simple", null, List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private String createInfo() throws Exception {
        String json = mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(INFO_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.layout").value("FULLSCREEN"))
                .andExpect(jsonPath("$.sizePercent").value(80))
                .andExpect(jsonPath("$.imageMode").value("NONE"))
                .andExpect(jsonPath("$.imageFit").value("CONTAIN"))
                .andExpect(jsonPath("$.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.imageAltFr").value("Affiche du concours"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions upload(String id, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/admin/video-modals/{id}/image", id).file(file).with(authentication(adminAuth())));
    }

    private static MockMultipartFile png() throws Exception {
        return new MockMultipartFile("file", "affiche.png", "image/png", image("png", 600, 900));
    }

    @Test
    @DisplayName("envoi d'une image valide : 200, URL lisible, audit ; puis usage en bannière")
    void uploadValid() throws Exception {
        String id = createInfo();

        upload(id, png()).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("https://r2.test/signed"));
        verify(auditService).log(eq("VIDEO_MODAL"), eq(UUID.fromString(id)), eq("VIDEO_MODAL_IMAGE_UPLOADED"), eq(ADMIN_ID), any());

        mockMvc.perform(put("/admin/video-modals/{id}", id).with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INFO_BODY.replace("\"imageMode\":\"NONE\"", "\"imageMode\":\"TOP\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageMode").value("TOP"))
                .andExpect(jsonPath("$.imageUrl").value("https://r2.test/signed"));
    }

    @Test
    @DisplayName("remplacement : l'ancienne image est supprimée du stockage")
    void replace() throws Exception {
        String id = createInfo();
        upload(id, png()).andExpect(status().isOk());
        doReturn("video-modals/k/1700000000001.png").when(storageService).storeAsset(any(), anyString(), anyString(), any());
        upload(id, png()).andExpect(status().isOk());
        verify(storageService).deleteQuietly("video-modals/k/1700000000000.png");
    }

    @Test
    @DisplayName("mode d'image sans image : 422 ; valeur d'enum inconnue : 400 ; taille hors plage : 422")
    void createValidation() throws Exception {
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INFO_BODY.replace("\"imageMode\":\"NONE\"", "\"imageMode\":\"POSTER\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INFO_BODY.replace("\"sizePercent\":80", "\"sizePercent\":29")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(INFO_BODY.replace("\"FULLSCREEN\"", "\"TOASTER\"")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("type annoncé faux (PNG déclaré en JPEG) ou fichier hors JPEG/PNG/WebP : 422, rien stocké")
    void wrongType() throws Exception {
        String id = createInfo();
        upload(id, new MockMultipartFile("file", "a.jpg", "image/jpeg", image("png", 10, 10)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        upload(id, new MockMultipartFile("file", "a.gif", "image/gif", new byte[]{'G', 'I', 'F', '8', '9', 'a'}))
                .andExpect(status().isUnprocessableEntity());
        upload(id, new MockMultipartFile("file", "a.jpg", "image/jpeg", "<script>alert(1)</script>".getBytes()))
                .andExpect(status().isUnprocessableEntity());
        verify(storageService, never()).storeAsset(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("fichier vide : 400 ; trop lourd (> 3 Mo) : 422 ; trop grand (> 2400 px) : 422 ; illisible : 422")
    void sizeLimits() throws Exception {
        String id = createInfo();
        upload(id, new MockMultipartFile("file", "a.png", "image/png", new byte[0])).andExpect(status().isBadRequest());
        upload(id, new MockMultipartFile("file", "a.jpg", "image/jpeg", jpegHeaderOnly(3 * 1024 * 1024 + 1)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("L'image ne peut pas dépasser 3 Mo"));
        upload(id, new MockMultipartFile("file", "a.png", "image/png", image("png", 2401, 20)))
                .andExpect(status().isUnprocessableEntity());
        upload(id, new MockMultipartFile("file", "a.jpg", "image/jpeg", jpegHeaderOnly(100)))
                .andExpect(status().isUnprocessableEntity());
        verify(storageService, never()).storeAsset(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("retrait : 200, image absente, mode repassé à NONE, fichier supprimé ; retrait répété : sans effet")
    void remove() throws Exception {
        String id = createInfo();
        upload(id, png()).andExpect(status().isOk());
        mockMvc.perform(put("/admin/video-modals/{id}", id).with(authentication(adminAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(INFO_BODY.replace("\"imageMode\":\"NONE\"", "\"imageMode\":\"POSTER\""))).andExpect(status().isOk());

        mockMvc.perform(delete("/admin/video-modals/{id}/image", id).with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").doesNotExist())
                .andExpect(jsonPath("$.imageMode").value("NONE"));
        verify(storageService).deleteQuietly("video-modals/k/1700000000000.png");
        verify(auditService).log(eq("VIDEO_MODAL"), eq(UUID.fromString(id)), eq("VIDEO_MODAL_IMAGE_REMOVED"), eq(ADMIN_ID), any());

        mockMvc.perform(delete("/admin/video-modals/{id}/image", id).with(authentication(adminAuth())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("image sur un modal VIDEO : 422 ; campagne inconnue : 404")
    void videoAndUnknown() throws Exception {
        String json = mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"V","youtubeUrl":"https://youtu.be/dQw4w9WgXcQ","titleFr":"Bienvenue",
                                 "triggers":["SEARCH_TAB"],"layout":"CENTERED","sizePercent":60,"imageMode":"TOP"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.layout").value("CENTERED"))
                .andExpect(jsonPath("$.imageMode").value("NONE"))
                .andReturn().getResponse().getContentAsString();
        upload(objectMapper.readTree(json).get("id").asText(), png()).andExpect(status().isUnprocessableEntity());
        upload(UUID.randomUUID().toString(), png()).andExpect(status().isNotFound());
        mockMvc.perform(delete("/admin/video-modals/{id}/image", UUID.randomUUID()).with(authentication(adminAuth())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("non-admin : 403 sur l'envoi et le retrait ; sans jeton : 401 ou 403")
    void forbidden() throws Exception {
        String id = createInfo();
        mockMvc.perform(multipart("/admin/video-modals/{id}/image", id).file(png()).with(authentication(userAuth())))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/admin/video-modals/{id}/image", id).with(authentication(userAuth())))
                .andExpect(status().isForbidden());
        int anonymous = mockMvc.perform(multipart("/admin/video-modals/{id}/image", id).file(png()))
                .andReturn().getResponse().getStatus();
        assertThat(anonymous).isIn(401, 403);
        verify(storageService, never()).storeAsset(any(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("app : eligible avec caps=layout renvoie format, taille et image ; sans caps, la campagne est retenue")
    void eligibleWithAndWithoutCaps() throws Exception {
        String id = createInfo();
        upload(id, png()).andExpect(status().isOk());
        mockMvc.perform(put("/admin/video-modals/{id}", id).with(authentication(adminAuth()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(INFO_BODY.replace("\"imageMode\":\"NONE\"", "\"imageMode\":\"POSTER\""))).andExpect(status().isOk());
        mockMvc.perform(patch("/admin/video-modals/{id}/active", id).with(authentication(adminAuth()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}")).andExpect(status().isOk());

        mockMvc.perform(get("/video-modals/eligible").param("lang", "en").param("caps", "layout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("INFO"))
                .andExpect(jsonPath("$[0].layout").value("FULLSCREEN"))
                .andExpect(jsonPath("$[0].sizePercent").value(80))
                .andExpect(jsonPath("$[0].imageMode").value("POSTER"))
                .andExpect(jsonPath("$[0].imageFit").value("CONTAIN"))
                .andExpect(jsonPath("$[0].imageUrl").value("https://r2.test/signed"))
                .andExpect(jsonPath("$[0].imageAlt").value("Contest poster"));

        mockMvc.perform(get("/video-modals/eligible").param("lang", "fr"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    @DisplayName("app : une campagne ordinaire (feuille du bas, sans image) reste envoyée sans caps, anciens champs inchangés")
    void ordinaryStillSentWithoutCaps() throws Exception {
        String json = mockMvc.perform(post("/admin/video-modals").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"V","youtubeUrl":"https://youtu.be/dQw4w9WgXcQ","durationSeconds":204,
                                 "titleFr":"Bienvenue","triggers":["SEARCH_TAB"]}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.layout").value("BOTTOM_SHEET"))
                .andExpect(jsonPath("$.sizePercent").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(json).get("id").asText();
        mockMvc.perform(patch("/admin/video-modals/{id}/active", id).with(authentication(adminAuth()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}")).andExpect(status().isOk());

        mockMvc.perform(get("/video-modals/eligible").param("lang", "fr"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].youtubeVideoId").value("dQw4w9WgXcQ"))
                .andExpect(jsonPath("$[0].layout").value("BOTTOM_SHEET"))
                .andExpect(jsonPath("$[0].imageUrl").doesNotExist());
        mockMvc.perform(get("/video-modals/eligible").param("lang", "fr").param("caps", "layout"))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
