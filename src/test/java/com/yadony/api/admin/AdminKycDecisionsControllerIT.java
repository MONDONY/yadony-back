package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.kyc.KycAdminReviewService;
import com.yadony.api.kyc.dto.KycAdminStatusResponse;
import com.yadony.api.kyc.dto.KycHistoryEntry;
import com.yadony.api.kyc.dto.KycQueueItemResponse;
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
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * File KYC et décisions d'administration : matrice de permissions (SUPPORT lit la file mais
 * ne décide jamais) et formes JSON du contrat consommé par dony-admin.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminKycDecisionsControllerIT — /admin/kyc/verifications et décisions")
class AdminKycDecisionsControllerIT {

    @Autowired MockMvc mockMvc;
    @MockitoBean KycAdminReviewService review;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken auth(AdminRole role, String... permissions) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "a@yadony.test", role, false, "uid-a");
        List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        for (String p : permissions) authorities.add(new SimpleGrantedAuthority(p));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    /** SUPPORT réel : USER_KYC sans KYC_DECIDE (cf. AdminRole.SUPPORT). */
    private static UsernamePasswordAuthenticationToken support() {
        return auth(AdminRole.SUPPORT, "USER_KYC");
    }

    private static UsernamePasswordAuthenticationToken admin() {
        return auth(AdminRole.ADMIN, "USER_KYC", "KYC_DECIDE");
    }

    private static KycAdminStatusResponse detail() {
        return new KycAdminStatusResponse(USER_ID, "VERIFIED", "VERIFIED", null, null, "sess_1", "Approved",
                null, null, null, false, "DIDIT", "APPROVED", LocalDateTime.of(2026, 9, 20, 10, 0),
                "a@yadony.test", "Pièce contrôlée", null,
                List.of(new KycHistoryEntry("KYC_VERIFIED_BY_ADMIN", LocalDateTime.of(2026, 9, 20, 10, 0),
                        "ADMIN", "a@yadony.test", "Pièce contrôlée")));
    }

    // ── File ──────────────────────────────────────────────────────────────────

    @Test
    void file_support_200_etFormeDUneLigne() throws Exception {
        KycQueueItemResponse item = new KycQueueItemResponse(USER_ID, "Awa Diop", "•••• 5678", "DIDIT",
                "PENDING", "PENDING", "IN_REVIEW", null, null, null, null, null,
                LocalDateTime.of(2026, 9, 26, 8, 0), 26L);
        when(review.queue(eq("IN_REVIEW"), any(), eq("awa"), eq(LocalDate.of(2026, 9, 1)),
                eq(LocalDate.of(2026, 9, 30)), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/kyc/verifications")
                        .param("status", "IN_REVIEW").param("query", "awa")
                        .param("from", "2026-09-01").param("to", "2026-09-30")
                        .with(authentication(support())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.content[0].userName").value("Awa Diop"))
                .andExpect(jsonPath("$.content[0].userPhone").value("•••• 5678"))
                .andExpect(jsonPath("$.content[0].provider").value("DIDIT"))
                .andExpect(jsonPath("$.content[0].kycStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].recordStatus").value("PENDING"))
                .andExpect(jsonPath("$.content[0].queueStatus").value("IN_REVIEW"))
                .andExpect(jsonPath("$.content[0].submittedAt").exists())
                .andExpect(jsonPath("$.content[0].waitingHours").value(26));
    }

    @Test
    void file_sansUserKyc_403() throws Exception {
        mockMvc.perform(get("/admin/kyc/verifications").with(authentication(auth(AdminRole.ADMIN))))
                .andExpect(status().isForbidden());
    }

    @Test
    void file_fournisseurInconnu_400() throws Exception {
        mockMvc.perform(get("/admin/kyc/verifications").param("provider", "ONFIDO")
                        .with(authentication(support())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void catalogueDesCodes_support_200() throws Exception {
        mockMvc.perform(get("/admin/kyc/rejection-codes").with(authentication(support())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("document_expired"));
    }

    // ── Détail ────────────────────────────────────────────────────────────────

    @Test
    void detail_champsAdditionnels() throws Exception {
        when(review.detail(USER_ID)).thenReturn(detail());

        mockMvc.perform(get("/admin/users/{id}/kyc", USER_ID).with(authentication(support())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeSessionId").value("sess_1"))
                .andExpect(jsonPath("$.decisionKind").value("APPROVED"))
                .andExpect(jsonPath("$.decidedByAdminEmail").value("a@yadony.test"))
                .andExpect(jsonPath("$.decisionReason").value("Pièce contrôlée"))
                .andExpect(jsonPath("$.providerSessionUrl").doesNotExist())
                .andExpect(jsonPath("$.history[0].action").value("KYC_VERIFIED_BY_ADMIN"))
                .andExpect(jsonPath("$.history[0].actorKind").value("ADMIN"))
                .andExpect(jsonPath("$.history[0].actorEmail").value("a@yadony.test"));
    }

    // ── Décisions : SUPPORT 403 ──────────────────────────────────────────────

    @Test
    void approve_support_403() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/kyc/approve", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Pièce contrôlée à la main\"}")
                        .with(authentication(support())))
                .andExpect(status().isForbidden());
        verify(review, never()).approve(any(), any(), any());
    }

    @Test
    void reject_support_403() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/kyc/reject", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"selfie_face_mismatch\",\"reason\":\"Selfie flou et sombre\"}")
                        .with(authentication(support())))
                .andExpect(status().isForbidden());
    }

    @Test
    void revoke_support_403() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/kyc/revoke", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"document_unverified_other\",\"reason\":\"Pièce déclarée volée par la police\"}")
                        .with(authentication(support())))
                .andExpect(status().isForbidden());
    }

    // ── Décisions : ADMIN ────────────────────────────────────────────────────

    @Test
    void approve_admin_200_rendLaFiche() throws Exception {
        when(review.detail(USER_ID)).thenReturn(detail());

        mockMvc.perform(post("/admin/users/{id}/kyc/approve", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Pièce contrôlée à la main\"}")
                        .with(authentication(admin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionKind").value("APPROVED"));
        verify(review).approve(USER_ID, ADMIN_ID, "Pièce contrôlée à la main");
    }

    @Test
    void approve_motifTropCourt_422() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/kyc/approve", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ok\"}")
                        .with(authentication(admin())))
                .andExpect(status().isUnprocessableEntity());
        verify(review, never()).approve(any(), any(), any());
    }

    @Test
    void approve_dejaVerifie_409() throws Exception {
        doThrow(new YadonyBusinessException(HttpStatus.CONFLICT, "kyc-already-verified", "Conflict", "déjà"))
                .when(review).approve(any(), any(), any());

        mockMvc.perform(post("/admin/users/{id}/kyc/approve", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Pièce contrôlée à la main\"}")
                        .with(authentication(admin())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("kyc-already-verified"));
    }

    @Test
    void reject_admin_200() throws Exception {
        when(review.detail(USER_ID)).thenReturn(detail());

        mockMvc.perform(post("/admin/users/{id}/kyc/reject", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"selfie_face_mismatch\",\"reason\":\"Selfie flou et sombre\"}")
                        .with(authentication(admin())))
                .andExpect(status().isOk());
        verify(review).reject(USER_ID, ADMIN_ID, "selfie_face_mismatch", "Selfie flou et sombre");
    }

    @Test
    void reject_sansCode_422() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/kyc/reject", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Selfie flou et sombre\"}")
                        .with(authentication(admin())))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void revoke_admin_motifDeMoinsDe20Caracteres_422() throws Exception {
        mockMvc.perform(post("/admin/users/{id}/kyc/revoke", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"document_unverified_other\",\"reason\":\"Pièce volée\"}")
                        .with(authentication(admin())))
                .andExpect(status().isUnprocessableEntity());
        verify(review, never()).revoke(any(), any(), any(), any());
    }

    @Test
    void revoke_admin_200() throws Exception {
        when(review.detail(USER_ID)).thenReturn(detail());

        mockMvc.perform(post("/admin/users/{id}/kyc/revoke", USER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"document_unverified_other\",\"reason\":\"Pièce déclarée volée par la police\"}")
                        .with(authentication(admin())))
                .andExpect(status().isOk());
        verify(review).revoke(USER_ID, ADMIN_ID, "document_unverified_other", "Pièce déclarée volée par la police");
    }
}
