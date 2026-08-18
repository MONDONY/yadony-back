package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.RemoveAnnouncementRequest;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementService;
import com.yadony.api.matching.AnnouncementStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AdminAnnouncementModerationController} — Lot B (correction 5,
 * revue task-2). Exigée par CLAUDE.md (« integration tests (controllers avec MockMvc) ») et
 * par la checklist pre-merge (@PreAuthorize des endpoints admin).
 *
 * Uses @SpringBootTest + MockMvc with inline authentication via
 * SecurityMockMvcRequestPostProcessors.authentication(), bypassing FirebaseTokenFilter —
 * même patron que {@code AdminAccountControllerIT}.
 *
 * Couvre : hasRole('ADMIN') and hasAuthority('CONTENT_REMOVE') (ADMIN ok, SUPPORT refusé,
 * qui ne reçoit pas CONTENT_REMOVE — cf. AdminRole.SUPPORT) et le mapping HTTP des 409
 * métier (bids acceptés en cours / annonce non retirée).
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminAnnouncementModerationControllerIT — /admin/announcements/{id}/remove|restore")
class AdminAnnouncementModerationControllerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    AnnouncementService announcementService;

    @MockitoBean
    UserRepository userRepository;

    private static final UUID ANNOUNCEMENT_ID = UUID.randomUUID();

    /** ADMIN principal — a CONTENT_REMOVE (complementOf(ADMIN_MANAGE), cf. AdminRole). */
    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-lotb");
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("CONTENT_REMOVE")));
    }

    /** SUPPORT principal — ne reçoit PAS CONTENT_REMOVE (cf. AdminRole.SUPPORT, liste explicite). */
    private static UsernamePasswordAuthenticationToken supportAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-lotb");
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private AnnouncementEntity buildAnnouncement(AnnouncementStatus status) {
        AnnouncementEntity a = new AnnouncementEntity();
        ReflectionTestUtils.setField(a, "id", ANNOUNCEMENT_ID);
        a.setTravelerId(UUID.randomUUID());
        a.setDepartureCity("Paris");
        a.setArrivalCity("Dakar");
        a.setDepartureDate(LocalDate.now().plusDays(10));
        a.setAvailableKg(BigDecimal.TEN);
        a.setPricePerKg(new BigDecimal("15.00"));
        a.setStatus(status);
        return a;
    }

    // ── POST /admin/announcements/{id}/remove ──────────────────────────────

    @Test
    @DisplayName("POST /remove — rôle SUPPORT (pas CONTENT_REMOVE) → 403")
    void remove_withSupportRole_returns403() throws Exception {
        mockMvc.perform(post("/admin/announcements/{id}/remove", ANNOUNCEMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RemoveAnnouncementRequest("contenu frauduleux")))
                        .with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /remove — rôle ADMIN (CONTENT_REMOVE) → 200 + ligne de table à jour")
    void remove_withAdminRole_returns200() throws Exception {
        AnnouncementEntity removed = buildAnnouncement(AnnouncementStatus.REMOVED_BY_ADMIN);
        when(announcementService.removeByAdmin(eq(ANNOUNCEMENT_ID), any(), anyString())).thenReturn(removed);

        mockMvc.perform(post("/admin/announcements/{id}/remove", ANNOUNCEMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RemoveAnnouncementRequest("contenu frauduleux")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ANNOUNCEMENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("REMOVED_BY_ADMIN"));
    }

    @Test
    @DisplayName("POST /remove — rôle ADMIN mais bids acceptés en cours → 409")
    void remove_withAcceptedBids_returns409() throws Exception {
        when(announcementService.removeByAdmin(eq(ANNOUNCEMENT_ID), any(), anyString()))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                        "announcement-has-accepted-bids", "Announcement Has Accepted Bids",
                        "Des colis acceptés sont en cours sur cette annonce."));

        mockMvc.perform(post("/admin/announcements/{id}/remove", ANNOUNCEMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RemoveAnnouncementRequest("peu importe")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("announcement-has-accepted-bids"));
    }

    @Test
    @DisplayName("POST /remove — motif vide → 422 (bean validation @NotBlank, cf. GlobalExceptionHandler)")
    void remove_blankReason_returns422() throws Exception {
        mockMvc.perform(post("/admin/announcements/{id}/remove", ANNOUNCEMENT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RemoveAnnouncementRequest("")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isUnprocessableEntity());
    }

    // ── POST /admin/announcements/{id}/restore ─────────────────────────────

    @Test
    @DisplayName("POST /restore — rôle SUPPORT (pas CONTENT_REMOVE) → 403")
    void restore_withSupportRole_returns403() throws Exception {
        mockMvc.perform(post("/admin/announcements/{id}/restore", ANNOUNCEMENT_ID)
                        .with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /restore — rôle ADMIN (CONTENT_REMOVE) → 200")
    void restore_withAdminRole_returns200() throws Exception {
        AnnouncementEntity restored = buildAnnouncement(AnnouncementStatus.ACTIVE);
        when(announcementService.restoreByAdmin(eq(ANNOUNCEMENT_ID), any())).thenReturn(restored);

        mockMvc.perform(post("/admin/announcements/{id}/restore", ANNOUNCEMENT_ID)
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /restore — rôle ADMIN mais annonce non retirée → 409")
    void restore_notRemoved_returns409() throws Exception {
        when(announcementService.restoreByAdmin(eq(ANNOUNCEMENT_ID), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                        "announcement-not-removed", "Announcement Not Removed",
                        "Cette annonce n'a pas été retirée par la modération."));

        mockMvc.perform(post("/admin/announcements/{id}/restore", ANNOUNCEMENT_ID)
                        .with(authentication(adminAuth())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("announcement-not-removed"));
    }
}
