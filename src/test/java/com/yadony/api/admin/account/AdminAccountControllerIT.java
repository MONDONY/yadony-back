package com.yadony.api.admin.account;

import com.yadony.api.admin.account.dto.AdminSummary;
import com.yadony.api.admin.account.dto.ChangePasswordRequest;
import com.yadony.api.admin.account.dto.CreateAdminRequest;
import com.yadony.api.admin.account.dto.CredentialsResponse;
import com.yadony.api.admin.account.dto.UpdateAdminRequest;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for {@link AdminAccountController} — Task 9.
 *
 * Uses @SpringBootTest + MockMvc with inline authentication via
 * SecurityMockMvcRequestPostProcessors.authentication(), bypassing FirebaseTokenFilter.
 *
 * Covers:
 * - 403 when caller lacks ADMIN_MANAGE (ADMIN or SUPPORT role)
 * - 200/201 when caller has ADMIN_MANAGE (SUPER_ADMIN)
 * - 409 on last-SUPER_ADMIN delete guard
 * - POST /admin/me/change-password accessible to any authenticated admin
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminAccountControllerIT — /admin/admins/** + /admin/me/change-password")
class AdminAccountControllerIT {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    AdminAccountService adminAccountService;

    @MockitoBean
    AdminUserRepository adminUserRepository;

    // -------------------------------------------------------------------------
    // Auth helpers
    // -------------------------------------------------------------------------

    /** SUPER_ADMIN principal — has ADMIN_MANAGE authority. */
    private static UsernamePasswordAuthenticationToken superAdminAuth() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AdminPrincipal principal = new AdminPrincipal(adminId, "admin.1@yadony.test", AdminRole.SUPER_ADMIN, false, "uid-super-admin-001");
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(
                        new SimpleGrantedAuthority("ADMIN_MANAGE"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_SUPER_ADMIN")
                )
        );
    }

    /** ADMIN principal — lacks ADMIN_MANAGE. */
    private static UsernamePasswordAuthenticationToken adminAuth() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        AdminPrincipal principal = new AdminPrincipal(adminId, "admin.2@yadony.test", AdminRole.ADMIN, false, "uid-admin-002");
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    /** SUPPORT principal — lacks ADMIN_MANAGE. */
    private static UsernamePasswordAuthenticationToken supportAuth() {
        UUID adminId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        AdminPrincipal principal = new AdminPrincipal(adminId, "support.1@yadony.test", AdminRole.SUPPORT, false, "uid-support-003");
        return new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
    }

    // -------------------------------------------------------------------------
    // Entity builder
    // -------------------------------------------------------------------------

    private AdminUserEntity buildEntity(UUID id, String email, AdminRole role) {
        AdminUserEntity e = new AdminUserEntity("firebase-" + email, email, role);
        e.setStatus(AdminStatus.ACTIVE);
        e.setMustChangePassword(false);
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    // -------------------------------------------------------------------------
    // GET /admin/admins — list
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /admin/admins without ADMIN_MANAGE (ADMIN role) → 403")
    void listAdmins_withAdminRole_returns403() throws Exception {
        mockMvc.perform(get("/admin/admins")
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/admins without ADMIN_MANAGE (SUPPORT role) → 403")
    void listAdmins_withSupportRole_returns403() throws Exception {
        mockMvc.perform(get("/admin/admins")
                        .with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /admin/admins with ADMIN_MANAGE (SUPER_ADMIN) → 200 + Page<AdminSummary>")
    void listAdmins_withAdminManage_returns200() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000010");
        AdminUserEntity entity = buildEntity(id, "admin.1@yadony.test", AdminRole.SUPER_ADMIN);
        AdminSummary summary = AdminSummary.from(entity);

        when(adminUserRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/admins")
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].email").value("admin.1@yadony.test"))
                .andExpect(jsonPath("$.content[0].role").value("SUPER_ADMIN"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /admin/admins?role=SUPPORT&status=ACTIVE → uses findByRoleAndStatus")
    void listAdmins_withRoleAndStatus_usesFilteredQuery() throws Exception {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000011");
        AdminUserEntity entity = buildEntity(id, "support.1@yadony.test", AdminRole.SUPPORT);

        when(adminUserRepository.findByRoleAndStatus(
                eq(AdminRole.SUPPORT), eq(AdminStatus.ACTIVE),
                any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/admins")
                        .param("role", "SUPPORT")
                        .param("status", "ACTIVE")
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].role").value("SUPPORT"));
    }

    // -------------------------------------------------------------------------
    // POST /admin/admins — create
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /admin/admins without ADMIN_MANAGE → 403")
    void createAdmin_withoutAdminManage_returns403() throws Exception {
        CreateAdminRequest req = new CreateAdminRequest("admin@yadony.test", AdminRole.ADMIN);

        mockMvc.perform(post("/admin/admins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /admin/admins with ADMIN_MANAGE → 201 + CredentialsResponse")
    void createAdmin_withAdminManage_returns201() throws Exception {
        CreateAdminRequest req = new CreateAdminRequest("admin@yadony.test", AdminRole.ADMIN);
        CredentialsResponse creds = new CredentialsResponse("admin.5@yadony.test", "TempPass123!");

        when(adminAccountService.createAdmin(any(CreateAdminRequest.class), any()))
                .thenReturn(creds);

        mockMvc.perform(post("/admin/admins")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("admin.5@yadony.test"))
                .andExpect(jsonPath("$.temporaryPassword").value("TempPass123!"));
    }

    // -------------------------------------------------------------------------
    // PATCH /admin/admins/{id} — update
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /admin/admins/{id} without ADMIN_MANAGE → 403")
    void updateAdmin_withoutAdminManage_returns403() throws Exception {
        UUID targetId = UUID.randomUUID();
        UpdateAdminRequest req = new UpdateAdminRequest(AdminRole.SUPPORT, null, null);

        mockMvc.perform(patch("/admin/admins/{id}", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /admin/admins/{id} with ADMIN_MANAGE → 200 + AdminSummary")
    void updateAdmin_withAdminManage_returns200() throws Exception {
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        UpdateAdminRequest req = new UpdateAdminRequest(AdminRole.SUPPORT, null, null);
        AdminUserEntity updated = buildEntity(targetId, "admin.2@yadony.test", AdminRole.SUPPORT);

        when(adminAccountService.updateAdmin(eq(targetId), any(UpdateAdminRequest.class), any()))
                .thenReturn(updated);

        mockMvc.perform(patch("/admin/admins/{id}", targetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(targetId.toString()))
                .andExpect(jsonPath("$.role").value("SUPPORT"));
    }

    // -------------------------------------------------------------------------
    // POST /admin/admins/{id}/reset-password
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /admin/admins/{id}/reset-password without ADMIN_MANAGE → 403")
    void resetPassword_withoutAdminManage_returns403() throws Exception {
        UUID targetId = UUID.randomUUID();

        mockMvc.perform(post("/admin/admins/{id}/reset-password", targetId)
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /admin/admins/{id}/reset-password with ADMIN_MANAGE → 200 + CredentialsResponse")
    void resetPassword_withAdminManage_returns200() throws Exception {
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000030");
        CredentialsResponse creds = new CredentialsResponse("admin.3@yadony.test", "NewSecure!Pass1");

        when(adminAccountService.resetPassword(eq(targetId), any()))
                .thenReturn(creds);

        mockMvc.perform(post("/admin/admins/{id}/reset-password", targetId)
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("admin.3@yadony.test"))
                .andExpect(jsonPath("$.temporaryPassword").value("NewSecure!Pass1"));
    }

    // -------------------------------------------------------------------------
    // DELETE /admin/admins/{id}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DELETE /admin/admins/{id} without ADMIN_MANAGE → 403")
    void deleteAdmin_withoutAdminManage_returns403() throws Exception {
        UUID targetId = UUID.randomUUID();

        mockMvc.perform(delete("/admin/admins/{id}", targetId)
                        .with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /admin/admins/{id} with ADMIN_MANAGE → 204")
    void deleteAdmin_withAdminManage_returns204() throws Exception {
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000040");
        // deleteAdmin is void — no stubbing needed for happy path

        mockMvc.perform(delete("/admin/admins/{id}", targetId)
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isNoContent());

        verify(adminAccountService).deleteAdmin(eq(targetId), any());
    }

    @Test
    @DisplayName("DELETE /admin/admins/{id} — last SUPER_ADMIN guard → 409")
    void deleteAdmin_lastSuperAdmin_returns409() throws Exception {
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000041");

        doThrow(new com.yadony.api.common.YadonyBusinessException(
                org.springframework.http.HttpStatus.CONFLICT,
                "ADMIN_LAST_SUPER_ADMIN",
                "Cannot delete last active SUPER_ADMIN",
                "There must always be at least one active SUPER_ADMIN"
        )).when(adminAccountService).deleteAdmin(eq(targetId), any());

        mockMvc.perform(delete("/admin/admins/{id}", targetId)
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("DELETE /admin/admins/{id} — self-delete guard → 409")
    void deleteAdmin_selfDelete_returns409() throws Exception {
        UUID targetId = UUID.fromString("00000000-0000-0000-0000-000000000042");

        doThrow(new com.yadony.api.common.YadonyBusinessException(
                org.springframework.http.HttpStatus.CONFLICT,
                "ADMIN_SELF_DELETE",
                "Cannot delete your own account",
                "You cannot delete your own admin account"
        )).when(adminAccountService).deleteAdmin(eq(targetId), any());

        mockMvc.perform(delete("/admin/admins/{id}", targetId)
                        .with(authentication(superAdminAuth())))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    // POST /admin/me/change-password
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /admin/me/change-password without ADMIN_MANAGE (ADMIN role) → 200")
    void changePassword_adminRole_returns200() throws Exception {
        ChangePasswordRequest req = new ChangePasswordRequest("NewPass!2024Xx");

        mockMvc.perform(post("/admin/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(authentication(loggedInSecondsAgo(adminAuth(), 30))))
                .andExpect(status().isOk());

        verify(adminAccountService).changeOwnPassword(any(), eq("NewPass!2024Xx"), any());
    }

    @Test
    @DisplayName("POST /admin/me/change-password without ADMIN_MANAGE (SUPPORT role) → 200")
    void changePassword_supportRole_returns200() throws Exception {
        ChangePasswordRequest req = new ChangePasswordRequest("NewPass!SupportX");

        mockMvc.perform(post("/admin/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(authentication(loggedInSecondsAgo(supportAuth(), 30))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /admin/me/change-password with SUPER_ADMIN → 200")
    void changePassword_superAdmin_returns200() throws Exception {
        ChangePasswordRequest req = new ChangePasswordRequest("SuperPass!XxYy99");

        mockMvc.perform(post("/admin/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req))
                        .with(authentication(loggedInSecondsAgo(superAdminAuth(), 30))))
                .andExpect(status().isOk());
    }

    // Audit sécu 21/10 : un jeton ancien (par exemple volé) ne fixe plus le mot de passe.
    @Test
    @DisplayName("POST /admin/me/change-password avec une connexion de plus de 5 min → 403 ADMIN_RECENT_LOGIN_REQUIRED")
    void changePassword_staleLogin_returns403() throws Exception {
        mockMvc.perform(post("/admin/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest("NewPass!2024Xx")))
                        .with(authentication(loggedInSecondsAgo(adminAuth(), 6 * 60))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_RECENT_LOGIN_REQUIRED"));

        org.mockito.Mockito.verifyNoInteractions(adminAccountService);
    }

    @Test
    @DisplayName("POST /admin/me/change-password sans jeton Firebase (auth_time inconnu) → 403")
    void changePassword_withoutFirebaseToken_returns403() throws Exception {
        mockMvc.perform(post("/admin/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ChangePasswordRequest("NewPass!2024Xx")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isForbidden());

        org.mockito.Mockito.verifyNoInteractions(adminAccountService);
    }

    /** Même principal, avec le jeton Firebase posé par le filtre en credentials. */
    private static UsernamePasswordAuthenticationToken loggedInSecondsAgo(
            UsernamePasswordAuthenticationToken base, long secondsAgo) {
        com.google.firebase.auth.FirebaseToken token =
                org.mockito.Mockito.mock(com.google.firebase.auth.FirebaseToken.class);
        org.mockito.Mockito.when(token.getClaims()).thenReturn(java.util.Map.of(
                "auth_time", java.time.Instant.now().minusSeconds(secondsAgo).getEpochSecond()));
        return new UsernamePasswordAuthenticationToken(base.getPrincipal(), token, base.getAuthorities());
    }
}
