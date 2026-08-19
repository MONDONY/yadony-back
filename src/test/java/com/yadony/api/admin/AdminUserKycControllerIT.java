package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.dto.KycResetRequest;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.kyc.KycAdminService;
import com.yadony.api.kyc.dto.KycAdminStatusResponse;
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
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot C — matrice de permission et mapping HTTP de /admin/users/{userId}/kyc.
 *
 * <p>SUPPORT possède USER_KYC (cf. AdminRole.SUPPORT) : il a donc accès aux deux endpoints.
 * Le test de refus utilise un principal admin PRIVÉ de USER_KYC — hasRole('ADMIN') seul
 * n'exclut personne, tout AdminPrincipal portant ROLE_ADMIN.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminUserKycControllerIT — /admin/users/{userId}/kyc")
class AdminUserKycControllerIT {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean KycAdminService kycAdminService;

    private static final UUID USER_ID = UUID.randomUUID();

    /** Admin complet : ROLE_ADMIN + USER_KYC. */
    private static UsernamePasswordAuthenticationToken adminAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "admin@yadony.test", AdminRole.ADMIN, false, "uid-admin-lotc");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("USER_KYC")));
    }

    /** SUPPORT : AdminRole.SUPPORT accorde USER_KYC — accès attendu. */
    private static UsernamePasswordAuthenticationToken supportAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "support@yadony.test", AdminRole.SUPPORT, false, "uid-support-lotc");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("USER_KYC")));
    }

    /** Admin dont USER_KYC a été révoquée par override : c'est l'authority qui doit mordre. */
    private static UsernamePasswordAuthenticationToken adminWithoutKycAuth() {
        AdminPrincipal principal = new AdminPrincipal(
                UUID.randomUUID(), "nokyc@yadony.test", AdminRole.ADMIN, false, "uid-nokyc-lotc");
        return new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
    }

    private static KycAdminStatusResponse sampleResponse() {
        return new KycAdminStatusResponse(USER_ID, "REJECTED", "REJECTED",
                "document_expired", "document_expired", "vs_001", "requires_input",
                "document_expired", "The document has expired.", null, false);
    }

    // ── GET /admin/users/{userId}/kyc ─────────────────────────────────────────

    @Test
    @DisplayName("GET — admin avec USER_KYC → 200 + les deux statuts et la session Stripe")
    void get_withUserKyc_returns200() throws Exception {
        when(kycAdminService.getForUser(USER_ID)).thenReturn(sampleResponse());

        mockMvc.perform(get("/admin/users/{userId}/kyc", USER_ID)
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("REJECTED"))
                .andExpect(jsonPath("$.verificationStatus").value("REJECTED"))
                .andExpect(jsonPath("$.stripeSessionId").value("vs_001"))
                .andExpect(jsonPath("$.stripeUnavailable").value(false));
    }

    @Test
    @DisplayName("GET — SUPPORT (qui possède USER_KYC) → 200")
    void get_withSupportRole_returns200() throws Exception {
        when(kycAdminService.getForUser(USER_ID)).thenReturn(sampleResponse());

        mockMvc.perform(get("/admin/users/{userId}/kyc", USER_ID)
                        .with(authentication(supportAuth())))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET — admin sans USER_KYC → 403")
    void get_withoutUserKyc_returns403() throws Exception {
        mockMvc.perform(get("/admin/users/{userId}/kyc", USER_ID)
                        .with(authentication(adminWithoutKycAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET — Stripe indisponible → 200 avec stripeUnavailable, jamais 500")
    void get_stripeUnavailable_returns200() throws Exception {
        when(kycAdminService.getForUser(USER_ID)).thenReturn(new KycAdminStatusResponse(
                USER_ID, "PENDING", "PENDING", null, null, "vs_001", null, null, null, null, true));

        mockMvc.perform(get("/admin/users/{userId}/kyc", USER_ID)
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeUnavailable").value(true))
                .andExpect(jsonPath("$.stripeStatus").doesNotExist());
    }

    // ── POST /admin/users/{userId}/kyc/reset ──────────────────────────────────

    @Test
    @DisplayName("POST /reset — admin sans USER_KYC → 403")
    void reset_withoutUserKyc_returns403() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/kyc/reset", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new KycResetRequest("document illisible")))
                        .with(authentication(adminWithoutKycAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /reset — admin avec USER_KYC → 200 + statuts remis à zéro")
    void reset_withUserKyc_returns200() throws Exception {
        when(kycAdminService.resetForUser(eq(USER_ID), any(), eq("document illisible")))
                .thenReturn(new KycAdminStatusResponse(USER_ID, "NOT_STARTED", "PENDING",
                        null, null, null, null, null, null, null, false));

        mockMvc.perform(post("/admin/users/{userId}/kyc/reset", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new KycResetRequest("document illisible")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kycStatus").value("NOT_STARTED"))
                .andExpect(jsonPath("$.stripeSessionId").doesNotExist());
    }

    @Test
    @DisplayName("POST /reset — motif vide → 422 (bean validation)")
    void reset_blankReason_returns422() throws Exception {
        mockMvc.perform(post("/admin/users/{userId}/kyc/reset", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new KycResetRequest("")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /reset — aucune vérification démarrée → 422 kyc-not-started (RFC 7807)")
    void reset_kycNotStarted_returns422WithCode() throws Exception {
        when(kycAdminService.resetForUser(eq(USER_ID), any(), any()))
                .thenThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "kyc-not-started", "Unprocessable",
                        "Cet utilisateur n'a jamais démarré de vérification d'identité"));

        mockMvc.perform(post("/admin/users/{userId}/kyc/reset", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new KycResetRequest("motif")))
                        .with(authentication(adminAuth())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("kyc-not-started"));
    }

    @Test
    @DisplayName("GET — utilisateur introuvable → 404 user-not-found (RFC 7807)")
    void get_userNotFound_returns404WithCode() throws Exception {
        when(kycAdminService.getForUser(USER_ID))
                .thenThrow(new YadonyBusinessException(HttpStatus.NOT_FOUND,
                        "user-not-found", "Not Found", "Utilisateur introuvable"));

        mockMvc.perform(get("/admin/users/{userId}/kyc", USER_ID)
                        .with(authentication(adminAuth())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }
}
