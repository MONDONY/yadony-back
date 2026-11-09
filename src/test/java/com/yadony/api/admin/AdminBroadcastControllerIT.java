package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.broadcast.AdminBroadcastEntity;
import com.yadony.api.admin.broadcast.AdminBroadcastRepository;
import com.yadony.api.admin.broadcast.BroadcastAudienceService;
import com.yadony.api.admin.broadcast.BroadcastService;
import com.yadony.api.admin.broadcast.BroadcastTarget;
import com.yadony.api.admin.broadcast.BroadcastTargetType;
import com.yadony.api.admin.broadcast.rule.AudienceRule;
import com.yadony.api.admin.broadcast.rule.RuleAudienceRepository.RecipientRow;
import com.yadony.api.admin.broadcast.segment.BroadcastSegmentService;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MvcResult;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot D — matrice de permission et mapping HTTP du broadcast.
 * SUPPORT ne recoit PAS NOTIFICATION_SEND : les trois routes doivent lui etre fermees.
 */
@SpringBootTest(properties = "yadony.broadcast.export-max-rows=3")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminBroadcastControllerIT — /admin/notifications/broadcast*")
class AdminBroadcastControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean BroadcastService broadcastService;
    @MockitoBean BroadcastAudienceService audienceService;
    @MockitoBean AdminBroadcastRepository broadcastRepository;
    @MockitoBean AuditService auditService;
    @MockitoBean BroadcastSegmentService segmentService;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-broadcast");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("NOTIFICATION_SEND"),
                        new SimpleGrantedAuthority("USER_VIEW")));
    }

    /** Droits surcharges par compte : NOTIFICATION_SEND sans USER_VIEW. */
    private static UsernamePasswordAuthenticationToken sendWithoutUserViewAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "send-only@yadony.test", AdminRole.ADMIN, false, "uid-send-only-broadcast");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("NOTIFICATION_SEND")));
    }

    /** SUPPORT — AdminRole.SUPPORT n'inclut pas NOTIFICATION_SEND. */
    private static UsernamePasswordAuthenticationToken supportAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-broadcast");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static AdminBroadcastEntity persisted(int recipientCount) {
        AdminBroadcastEntity entity = new AdminBroadcastEntity(
                "Maintenance", "Service indisponible ce soir.", BroadcastTargetType.ALL,
                null, null, null, recipientCount, ADMIN_ID);
        ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
        return entity;
    }

    private String body(String json) {
        return json;
    }

    // ── POST /admin/notifications/broadcast ───────────────────────────────────

    @Test
    @DisplayName("POST — SUPPORT (sans NOTIFICATION_SEND) → 403 et rien n'est envoye")
    void send_withSupportRole_returns403() throws Exception {
        mockMvc.perform(post("/admin/notifications/broadcast")
                        .with(authentication(supportAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"title":"T","body":"B","target":{"type":"ALL"}}
                                """)))
                .andExpect(status().isForbidden());

        verify(broadcastService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST — ADMIN → 202, historique enregistre, diffusion declenchee, audit ecrit")
    void send_withAdmin_returns202AndAudits() throws Exception {
        AdminBroadcastEntity saved = persisted(37);
        when(broadcastService.record(eq("Maintenance"), eq("Service indisponible ce soir."),
                any(BroadcastTarget.class), eq(ADMIN_ID), any(), any())).thenReturn(saved);

        mockMvc.perform(post("/admin/notifications/broadcast")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"title":"Maintenance","body":"Service indisponible ce soir.",
                                 "target":{"type":"ALL"}}
                                """)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.recipientCount").value(37))
                .andExpect(jsonPath("$.targetType").value("ALL"));

        // Sans le champ push (admin pas encore à jour) : comportement d'avant, avec push.
        verify(broadcastService).dispatchAsync(eq(saved.getId()), eq("Maintenance"),
                eq("Service indisponible ce soir."), any(BroadcastTarget.class), eq(true));
        verify(auditService).log(eq("admin_broadcast"), eq(saved.getId()),
                eq("BROADCAST_SENT"), eq(ADMIN_ID), any(Map.class));
    }

    @Test
    @DisplayName("POST — push:false → diffusion sans push, choix trace dans l'audit")
    void send_withPushFalse_dispatchesWithoutPush() throws Exception {
        AdminBroadcastEntity saved = persisted(12);
        when(broadcastService.record(eq("Défi du jour"), eq("Rendez-vous à 21 h."),
                any(BroadcastTarget.class), eq(ADMIN_ID), any(), any())).thenReturn(saved);

        mockMvc.perform(post("/admin/notifications/broadcast")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"title":"Défi du jour","body":"Rendez-vous à 21 h.",
                                 "target":{"type":"ALL"},"push":false}
                                """)))
                .andExpect(status().isAccepted());

        // Titre et corps diffusés = ceux de la ligne d'historique (ici le stub persisted()).
        verify(broadcastService).dispatchAsync(eq(saved.getId()), eq(saved.getTitle()),
                eq(saved.getBody()), any(BroadcastTarget.class), eq(false));
        verify(auditService).log(eq("admin_broadcast"), eq(saved.getId()), eq("BROADCAST_SENT"),
                eq(ADMIN_ID), argThat((Map<String, Object> m) -> Boolean.FALSE.equals(m.get("push"))));
    }

    /**
     * Titre vide : @NotBlank echoue en MethodArgumentNotValidException, mappee par
     * GlobalExceptionHandler.handleValidation en 422 (pas 400) — voir aussi
     * AdminGdprControllerIT.execute_blankReason_returns422, meme mecanisme.
     */
    @Test
    @DisplayName("POST — titre vide → 422 (validation Bean), rien n'est envoye")
    void send_withBlankTitle_returns422() throws Exception {
        mockMvc.perform(post("/admin/notifications/broadcast")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"title":"  ","body":"B","target":{"type":"ALL"}}
                                """)))
                .andExpect(status().isUnprocessableEntity());

        verify(broadcastService, never()).dispatchAsync(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("POST — corridor sans ville d'arrivee → 422 RFC 7807")
    void send_withIncompleteCorridor_returns422() throws Exception {
        mockMvc.perform(post("/admin/notifications/broadcast")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"title":"T","body":"B","target":{"type":"CORRIDOR","origin":"Paris"}}
                                """)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").exists());
    }

    // ── POST /admin/notifications/broadcast/preview ───────────────────────────

    @Test
    @DisplayName("preview — SUPPORT → 403")
    void preview_withSupportRole_returns403() throws Exception {
        mockMvc.perform(post("/admin/notifications/broadcast/preview")
                        .with(authentication(supportAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"type":"ALL"}
                                """)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("preview — ADMIN → 200 avec le nombre de destinataires, sans rien envoyer")
    void preview_withAdmin_returnsCountOnly() throws Exception {
        when(audienceService.count(any(BroadcastTarget.class))).thenReturn(128L);

        mockMvc.perform(post("/admin/notifications/broadcast/preview")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("""
                                {"type":"CORRIDOR","origin":"Paris","destination":"Dakar"}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(128));

        verify(broadcastService, never()).dispatchAsync(any(), any(), any(), any(), anyBoolean());
    }

    // ── GET /admin/notifications/broadcasts ───────────────────────────────────

    @Test
    @DisplayName("GET historique — SUPPORT → 403")
    void history_withSupportRole_returns403() throws Exception {
        mockMvc.perform(get("/admin/notifications/broadcasts").with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET historique — ADMIN → 200, page Spring brute")
    void history_withAdmin_returnsPage() throws Exception {
        when(broadcastRepository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(persisted(5)), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/notifications/broadcasts").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].recipientCount").value(5))
                .andExpect(jsonPath("$.content[0].title").value("Maintenance"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    // ── Ciblage nominatif (USER) ─────────────────────────────────────────────

    private static final UUID TARGET_USER = UUID.randomUUID();

    private String userTargetJson() {
        return """
                {"title":"Votre dossier","body":"Merci de nous recontacter.",
                 "target":{"type":"USER","userId":"%s"}}
                """.formatted(TARGET_USER);
    }

    @Test
    @DisplayName("POST USER — compte banni, en suppression ou supprime : 422 broadcast-user-not-reachable, rien d'enregistre")
    void send_toUnreachableUser_returns422() throws Exception {
        when(audienceService.targetUser(any(BroadcastTarget.class))).thenReturn(
                java.util.Optional.of(new BroadcastAudienceService.TargetUser(TARGET_USER, "Awa D.", false)));

        mockMvc.perform(post("/admin/notifications/broadcast")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userTargetJson()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("broadcast-user-not-reachable"));

        verify(broadcastService, never()).record(any(), any(), any(), any(), any(), any());
        verify(broadcastService, never()).dispatchAsync(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("POST USER — utilisateur inconnu : 404 user-not-found en problem+json")
    void send_toUnknownUser_returns404() throws Exception {
        when(audienceService.targetUser(any(BroadcastTarget.class))).thenThrow(
                new com.yadony.api.common.YadonyBusinessException(org.springframework.http.HttpStatus.NOT_FOUND,
                        "user-not-found", "Not Found", "Utilisateur introuvable"));

        mockMvc.perform(post("/admin/notifications/broadcast")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userTargetJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));

        verify(broadcastService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST USER — compte joignable : 202, envoi enregistre")
    void send_toReachableUser_returns202() throws Exception {
        when(audienceService.targetUser(any(BroadcastTarget.class))).thenReturn(
                java.util.Optional.of(new BroadcastAudienceService.TargetUser(TARGET_USER, "Awa D.", true)));
        AdminBroadcastEntity saved = new AdminBroadcastEntity("Votre dossier", "Merci de nous recontacter.",
                BroadcastTargetType.USER, null, null, TARGET_USER, 1, ADMIN_ID);
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
        when(broadcastService.record(any(), any(), any(BroadcastTarget.class), eq(ADMIN_ID), any(), any())).thenReturn(saved);

        mockMvc.perform(post("/admin/notifications/broadcast")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userTargetJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.recipientCount").value(1));
    }

    @Test
    @DisplayName("preview USER — renvoie le nombre (0 ou 1) et le nom du compte cible")
    void preview_userTarget_returnsCountAndName() throws Exception {
        when(audienceService.count(any(BroadcastTarget.class))).thenReturn(0L);
        when(audienceService.targetUser(any(BroadcastTarget.class))).thenReturn(
                java.util.Optional.of(new BroadcastAudienceService.TargetUser(TARGET_USER, "Awa D.", false)));

        mockMvc.perform(post("/admin/notifications/broadcast/preview")
                        .with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"USER","userId":"%s"}
                                """.formatted(TARGET_USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(0))
                .andExpect(jsonPath("$.targetUserName").value("Awa D."))
                .andExpect(jsonPath("$.targetUserReachable").value(false));
    }

    // ── Moteur de ciblage (lot 1) ─────────────────────────────────────────────

    private static final String RULE_JSON = """
            {"op":"AND","children":[{"field":"kyc","operator":"IN","value":["VERIFIED"]}]}""";

    private static String ruleBody(String targetExtra) {
        return "{\"title\":\"T\",\"body\":\"B\",\"target\":{\"type\":\"RULE\"" + targetExtra + "}}";
    }

    private static AdminBroadcastEntity persistedRule(int n) {
        AdminBroadcastEntity e = new AdminBroadcastEntity("T", "B", BroadcastTargetType.RULE,
                null, null, null, n, ADMIN_ID, "{\"children\":[],\"op\":\"AND\"}", null, null);
        ReflectionTestUtils.setField(e, "id", UUID.randomUUID());
        return e;
    }

    @Test
    @DisplayName("ancienContrat_SENDERS_sansRule_inchange : même 202, même compteur, pas de règle")
    void ancienContrat_SENDERS_sansRule_inchange() throws Exception {
        AdminBroadcastEntity saved = new AdminBroadcastEntity("T", "B", BroadcastTargetType.SENDERS,
                null, null, null, 9, ADMIN_ID);
        ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
        when(broadcastService.record(eq("T"), eq("B"), any(BroadcastTarget.class), eq(ADMIN_ID), any(), any()))
                .thenReturn(saved);

        mockMvc.perform(post("/admin/notifications/broadcast").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"body\":\"B\",\"target\":{\"type\":\"SENDERS\"}}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.recipientCount").value(9))
                .andExpect(jsonPath("$.targetType").value("SENDERS"))
                .andExpect(jsonPath("$.audienceRule").doesNotExist());
        verify(auditService).log(eq("admin_broadcast"), eq(saved.getId()), eq("BROADCAST_SENT"), eq(ADMIN_ID),
                argThat((Map<String, Object> m) -> m.get("ruleHash") != null && Integer.valueOf(1).equals(m.get("conditionCount"))));
    }

    @Test
    @DisplayName("type SENDERS avec une règle : la règle est ignorée")
    void typeHeritage_avecRule_regleIgnoree() throws Exception {
        AdminBroadcastEntity saved = persisted(1);
        when(broadcastService.record(any(), any(), any(BroadcastTarget.class), eq(ADMIN_ID), any(), any()))
                .thenReturn(saved);
        mockMvc.perform(post("/admin/notifications/broadcast").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"T\",\"body\":\"B\",\"target\":{\"type\":\"SENDERS\",\"rule\":"
                                + RULE_JSON + "}}"))
                .andExpect(status().isAccepted());
        verify(broadcastService).record(any(), any(),
                argThat((BroadcastTarget t) -> t.type() == BroadcastTargetType.SENDERS && t.rule() == null),
                eq(ADMIN_ID), any(), any());
    }

    @Test
    @DisplayName("rule_etSegmentId_ensemble_422")
    void rule_etSegmentId_ensemble_422() throws Exception {
        mockMvc.perform(post("/admin/notifications/broadcast").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleBody(",\"rule\":" + RULE_JSON + ",\"segmentId\":\"" + UUID.randomUUID() + "\"")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("broadcast-target-invalid"));
    }

    @Test
    @DisplayName("rule_absente_422")
    void rule_absente_422() throws Exception {
        mockMvc.perform(post("/admin/notifications/broadcast").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(ruleBody("")))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/admin/notifications/broadcast").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content(ruleBody(",\"rule\":null")))
                .andExpect(status().isUnprocessableEntity());
        verify(broadcastService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("règle invalide (champ inconnu) : 422 broadcast-rule-invalid, jamais 500")
    void rule_invalide_422() throws Exception {
        mockMvc.perform(post("/admin/notifications/broadcast/preview").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"RULE\",\"rule\":{\"op\":\"AND\",\"children\":[{\"field\":\"zzz\",\"operator\":\"IS\",\"value\":true}]}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("broadcast-rule-invalid"));
    }

    @Test
    @DisplayName("segmentSupprime_404")
    void segmentSupprime_404() throws Exception {
        UUID segId = UUID.randomUUID();
        when(segmentService.requireRule(segId)).thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND,
                "broadcast-segment-not-found", "Not Found", "Segment introuvable"));
        mockMvc.perform(post("/admin/notifications/broadcast").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleBody(",\"segmentId\":\"" + segId + "\"")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("broadcast-segment-not-found"));
        verify(broadcastService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("envoi par segment : règle du segment, id et nom transmis, audit avec segmentId")
    void send_parSegment_transmetSegment() throws Exception {
        UUID segId = UUID.randomUUID();
        AudienceRule.Group rule = AudienceRule.parse(objectMapper.readTree(RULE_JSON));
        when(segmentService.requireRule(segId)).thenReturn(
                new BroadcastSegmentService.SegmentSnapshot(segId, "Vérifiés", rule));
        AdminBroadcastEntity saved = persistedRule(4);
        when(broadcastService.record(any(), any(), any(BroadcastTarget.class), eq(ADMIN_ID), eq(segId), eq("Vérifiés")))
                .thenReturn(saved);
        mockMvc.perform(post("/admin/notifications/broadcast").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ruleBody(",\"segmentId\":\"" + segId + "\"")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.audienceRule.op").value("AND"));
        verify(auditService).log(eq("admin_broadcast"), eq(saved.getId()), eq("BROADCAST_SENT"), eq(ADMIN_ID),
                argThat((Map<String, Object> m) -> segId.equals(m.get("segmentId"))
                        && rule.hash().equals(m.get("ruleHash"))
                        && Integer.valueOf(1).equals(m.get("conditionCount"))));
    }

    @Test
    @DisplayName("preview_rule_renvoiePushReachable")
    void preview_rule_renvoiePushReachable() throws Exception {
        when(audienceService.count(any(BroadcastTarget.class))).thenReturn(10L);
        when(audienceService.countPushReachable(any(BroadcastTarget.class))).thenReturn(7L);
        mockMvc.perform(post("/admin/notifications/broadcast/preview").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"RULE\",\"rule\":" + RULE_JSON + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(10))
                .andExpect(jsonPath("$.pushReachableCount").value(7));
    }

    @Test
    @DisplayName("preview legacy : recipientCount toujours présent")
    void preview_legacy_gardeRecipientCount() throws Exception {
        when(audienceService.count(any(BroadcastTarget.class))).thenReturn(3L);
        when(audienceService.countPushReachable(any(BroadcastTarget.class))).thenReturn(2L);
        mockMvc.perform(post("/admin/notifications/broadcast/preview").with(authentication(adminAuth()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"ALL\"}"))
                .andExpect(jsonPath("$.recipientCount").value(3))
                .andExpect(jsonPath("$.pushReachableCount").value(2));
    }

    @Test
    @DisplayName("fields_contientLesQuatreFamilles")
    void fields_contientLesQuatreFamilles() throws Exception {
        MvcResult r = mockMvc.perform(get("/admin/notifications/broadcast/fields").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].operators[0].label").exists())
                .andExpect(jsonPath("$[0].operators[0].valueType").exists())
                .andReturn();
        var tree = objectMapper.readTree(r.getResponse().getContentAsString());
        java.util.Set<String> families = new java.util.LinkedHashSet<>();
        tree.forEach(n -> families.add(n.get("family").asText()));
        org.assertj.core.api.Assertions.assertThat(families)
                .containsExactly("STATUS", "ACTIVITY", "GEO", "MONEY");
    }

    @Test
    @DisplayName("fields — SUPPORT → 403")
    void fields_support_403() throws Exception {
        mockMvc.perform(get("/admin/notifications/broadcast/fields").with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("presets — admin → 200 avec règle et modèles fr/en ; aucune donnée d'utilisateur lue")
    void presets_admin_200() throws Exception {
        mockMvc.perform(get("/admin/notifications/broadcast/presets").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").exists())
                .andExpect(jsonPath("$[0].rule.op").value("AND"))
                .andExpect(jsonPath("$[0].messages.fr.title").exists())
                .andExpect(jsonPath("$[0].messages.en.body").exists());
        org.mockito.Mockito.verifyNoInteractions(audienceService);
    }

    @Test
    @DisplayName("presets — SUPPORT → 403")
    void presets_support_403() throws Exception {
        mockMvc.perform(get("/admin/notifications/broadcast/presets").with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("recipients — NOTIFICATION_SEND sans USER_VIEW → 403 (noms d'utilisateurs)")
    void recipients_sansUserView_403() throws Exception {
        mockMvc.perform(post("/admin/notifications/broadcast/recipients")
                        .with(authentication(sendWithoutUserViewAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ALL\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("recipients.csv — NOTIFICATION_SEND sans USER_VIEW → 403 (noms d'utilisateurs)")
    void recipientsCsv_sansUserView_403() throws Exception {
        mockMvc.perform(post("/admin/notifications/broadcast/recipients.csv")
                        .with(authentication(sendWithoutUserViewAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ALL\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("recipients_pagine_bornéA100")
    void recipients_pagine_borneA100() throws Exception {
        when(audienceService.recipients(any(BroadcastTarget.class), anyInt(), anyInt())).thenAnswer(inv -> {
            int p = inv.getArgument(1);
            int s = inv.getArgument(2);
            return new PageImpl<>(List.of(new RecipientRow(UUID.randomUUID(), "Awa D.", "FR", "fr", true,
                    Instant.parse("2026-01-02T03:04:05Z"))), PageRequest.of(p, s), 1);
        });
        mockMvc.perform(post("/admin/notifications/broadcast/recipients?page=-4&size=5000")
                        .with(authentication(adminAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ALL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].displayName").value("Awa D."))
                .andExpect(jsonPath("$.content[0].pushReachable").value(true))
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.number").value(0));
        mockMvc.perform(post("/admin/notifications/broadcast/recipients?size=0")
                        .with(authentication(adminAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ALL\"}"))
                .andExpect(jsonPath("$.size").value(1));
    }

    private void stubRows(long total, RecipientRow... rows) {
        when(audienceService.count(any(BroadcastTarget.class))).thenReturn(total);
        when(audienceService.recipients(any(BroadcastTarget.class), anyInt(), anyInt())).thenAnswer(inv -> {
            int p = inv.getArgument(1);
            int s = inv.getArgument(2);
            int from = Math.min(p * s, rows.length);
            int to = Math.min(from + s, rows.length);
            return new PageImpl<>(List.of(rows).subList(from, to), PageRequest.of(p, s), rows.length);
        });
    }

    @Test
    @DisplayName("csv_enTeteEtBom : BOM, ; , échappement, neutralisation des formules, audit")
    void csv_enTeteEtBom() throws Exception {
        stubRows(3,
                new RecipientRow(UUID.randomUUID(), "A;B \"C\"\nD", "FR", "fr", true, Instant.parse("2026-01-02T03:04:05Z")),
                new RecipientRow(UUID.randomUUID(), "=HYPERLINK(x)", null, null, false, Instant.parse("2026-01-02T03:04:05Z")),
                new RecipientRow(UUID.randomUUID(), "Moussa", "SN", "en", true, Instant.parse("2026-01-02T03:04:05Z")));
        MvcResult r = mockMvc.perform(post("/admin/notifications/broadcast/recipients.csv")
                        .with(authentication(adminAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ALL\"}"))
                .andExpect(status().isOk())
                .andReturn();
        org.assertj.core.api.Assertions.assertThat(r.getResponse().getContentType()).startsWith("text/csv");
        org.assertj.core.api.Assertions.assertThat(r.getResponse().getHeader("Content-Disposition"))
                .matches(".*destinataires-\\d{8}-\\d{4}\\.csv.*");
        String csv = new String(r.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(csv).startsWith("\uFEFFid;nom;pays;langue;push;inscrit_le\n");
        org.assertj.core.api.Assertions.assertThat(csv).contains(";\"A;B \"\"C\"\"\nD\";FR;fr;oui;");
        org.assertj.core.api.Assertions.assertThat(csv).contains(";'=HYPERLINK(x);;;non;");
        verify(auditService).log(eq("admin_broadcast"), any(), eq("BROADCAST_RECIPIENTS_EXPORTED"), eq(ADMIN_ID),
                argThat((Map<String, Object> m) -> Integer.valueOf(3).equals(m.get("rowCount")) && m.get("ruleHash") != null));
    }

    @Test
    @DisplayName("csv_auDelaDeLaBorne_422 (borne abaissée à 3)")
    void csv_auDelaDe50000_422() throws Exception {
        when(audienceService.count(any(BroadcastTarget.class))).thenReturn(4L);
        mockMvc.perform(post("/admin/notifications/broadcast/recipients.csv")
                        .with(authentication(adminAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"ALL\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("broadcast-export-too-large"));
        verify(auditService, never()).log(any(), any(), eq("BROADCAST_RECIPIENTS_EXPORTED"), any(), any());
    }

    @Test
    @DisplayName("csv USER : ruleHash null dans l'audit")
    void csv_user_ruleHashNull() throws Exception {
        stubRows(1, new RecipientRow(TARGET_USER, "Awa", "FR", "fr", true, Instant.parse("2026-01-02T03:04:05Z")));
        mockMvc.perform(post("/admin/notifications/broadcast/recipients.csv")
                        .with(authentication(adminAuth())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"USER\",\"userId\":\"" + TARGET_USER + "\"}"))
                .andExpect(status().isOk());
        verify(auditService).log(eq("admin_broadcast"), any(), eq("BROADCAST_RECIPIENTS_EXPORTED"), eq(ADMIN_ID),
                argThat((Map<String, Object> m) -> m.containsKey("ruleHash") && m.get("ruleHash") == null));
    }

    @Test
    @DisplayName("historique : audienceRule est un objet JSON, null pour une ligne ancienne")
    void history_audienceRule_objet() throws Exception {
        when(broadcastRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(
                new PageImpl<>(List.of(persistedRule(2), persisted(5)), PageRequest.of(0, 20), 2));
        mockMvc.perform(get("/admin/notifications/broadcasts").with(authentication(adminAuth())))
                .andExpect(jsonPath("$.content[0].audienceRule.op").value("AND"))
                .andExpect(jsonPath("$.content[1].audienceRule").doesNotExist());
    }
}
