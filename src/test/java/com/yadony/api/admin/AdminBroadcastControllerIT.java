package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.broadcast.AdminBroadcastEntity;
import com.yadony.api.admin.broadcast.AdminBroadcastRepository;
import com.yadony.api.admin.broadcast.BroadcastAudienceService;
import com.yadony.api.admin.broadcast.BroadcastService;
import com.yadony.api.admin.broadcast.BroadcastTarget;
import com.yadony.api.admin.broadcast.BroadcastTargetType;
import com.yadony.api.common.AuditService;
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
@SpringBootTest
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

    private static final UUID ADMIN_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                ADMIN_ID, "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-broadcast");
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

        verify(broadcastService, never()).record(any(), any(), any(), any());
    }

    @Test
    @DisplayName("POST — ADMIN → 202, historique enregistre, diffusion declenchee, audit ecrit")
    void send_withAdmin_returns202AndAudits() throws Exception {
        AdminBroadcastEntity saved = persisted(37);
        when(broadcastService.record(eq("Maintenance"), eq("Service indisponible ce soir."),
                any(BroadcastTarget.class), eq(ADMIN_ID))).thenReturn(saved);

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

        verify(broadcastService).dispatchAsync(eq(saved.getId()), eq("Maintenance"),
                eq("Service indisponible ce soir."), any(BroadcastTarget.class));
        verify(auditService).log(eq("admin_broadcast"), eq(saved.getId()),
                eq("BROADCAST_SENT"), eq(ADMIN_ID), any(Map.class));
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

        verify(broadcastService, never()).dispatchAsync(any(), any(), any(), any());
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

        verify(broadcastService, never()).dispatchAsync(any(), any(), any(), any());
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
}
