package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.GdprExecuteRequest;
import com.yadony.api.auth.AdminGdprService;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.common.YadonyBusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot C — matrice de permission et mapping HTTP de la file RGPD.
 * SUPPORT ne reçoit PAS USER_GDPR_DELETE (cf. AdminRole.SUPPORT) : les deux endpoints
 * doivent lui être fermés.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminGdprControllerIT — /admin/users/gdpr-requests & /gdpr-execute")
class AdminGdprControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AdminGdprService adminGdprService;
    @MockitoBean FirebaseContactService firebaseContact;

    private static final UUID USER_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-gdpr");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("USER_GDPR_DELETE")));
    }

    /** SUPPORT — AdminRole.SUPPORT n'inclut pas USER_GDPR_DELETE. */
    private static UsernamePasswordAuthenticationToken supportAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-gdpr");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private UserEntity buildPendingUser() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        u.setFirebaseUid("uid-001");
        u.setFirstName("Jean");
        u.setLastName("Dupont");
        u.setStatus(UserStatus.PENDING_DELETION);
        u.setDeletionRequestedAt(Instant.now().minus(12, ChronoUnit.DAYS));
        return u;
    }

    // ── GET /admin/users/gdpr-requests ────────────────────────────────────────

    @Test
    @DisplayName("GET — SUPPORT (sans USER_GDPR_DELETE) → 403")
    void list_withSupportRole_returns403() throws Exception {
        mockMvc.perform(get("/admin/users/gdpr-requests").with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET — la route littérale l'emporte sur /admin/users/{userId}, et l'âge est calculé")
    void list_literalPathWinsOverUuidTemplate() throws Exception {
        when(adminGdprService.listDeletionRequests(any()))
                .thenReturn(new PageImpl<>(List.of(buildPendingUser()), PageRequest.of(0, 20), 1));
        when(firebaseContact.getContacts(anyList()))
                .thenReturn(Map.of("uid-001", new FirebaseContactService.Contact("+33600000000", "jean@x.fr")));

        mockMvc.perform(get("/admin/users/gdpr-requests").with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(USER_ID.toString()))
                .andExpect(jsonPath("$.content[0].email").value("jean@x.fr"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING_DELETION"))
                .andExpect(jsonPath("$.content[0].ageDays").value(12));
    }

    // ── POST /admin/users/{userId}/gdpr-execute ───────────────────────────────

    @Test
    @DisplayName("POST — SUPPORT (sans USER_GDPR_DELETE) → 403")
    void execute_withSupportRole_returns403() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/gdpr-execute", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GdprExecuteRequest("motif")))
                        .with(authentication(supportAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST — admin avec USER_GDPR_DELETE → 204 et délégation au service")
    void execute_withPermission_returns204() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/gdpr-execute", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new GdprExecuteRequest("demande utilisateur confirmée")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isNoContent());

        verify(adminGdprService).executeDeletion(eq(USER_ID), any(), eq("demande utilisateur confirmée"));
    }

    @Test
    @DisplayName("POST — escrow actif → 422 active-transactions (RFC 7807), jamais 409")
    void execute_activeEscrow_returns422() throws Exception {
        doThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "active-transactions",
                "Unprocessable", "Impossible — cet utilisateur a des transactions en cours"))
                .when(adminGdprService).executeDeletion(eq(USER_ID), any(), any());

        mockMvc.perform(post("/admin/users/{userId}/gdpr-execute", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GdprExecuteRequest("motif")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("active-transactions"));
    }

    @Test
    @DisplayName("POST — solde wallet non vide → 422 wallet-balance-not-empty")
    void execute_walletBalance_returns422() throws Exception {
        doThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-balance-not-empty",
                "Unprocessable", "Solde wallet non nul"))
                .when(adminGdprService).executeDeletion(eq(USER_ID), any(), any());

        mockMvc.perform(post("/admin/users/{userId}/gdpr-execute", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GdprExecuteRequest("motif")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("wallet-balance-not-empty"));
    }

    @Test
    @DisplayName("POST — motif vide → 422 (bean validation)")
    void execute_blankReason_returns422() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/gdpr-execute", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new GdprExecuteRequest("")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isUnprocessableEntity());
    }
}
