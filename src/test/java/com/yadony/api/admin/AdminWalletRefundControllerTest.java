package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPermissions;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import org.springframework.security.core.GrantedAuthority;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.payments.wallet.WalletRefundRequestEntity;
import com.yadony.api.payments.wallet.WalletRefundRequestRepository;
import com.yadony.api.payments.wallet.WalletRefundRequestStatus;
import com.yadony.api.payments.wallet.WalletRefundRetryService;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import com.yadony.api.payments.wallet.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("AdminWalletRefundControllerTest — /admin/wallet-refund-requests")
class AdminWalletRefundControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean WalletRefundRequestRepository refundRequestRepository;
    @MockitoBean WalletService walletService;
    @MockitoBean AuditService auditService;
    @MockitoBean AdminAlertService adminAlertService;
    @MockitoBean WalletRefundRetryService walletRefundRetryService;
    @Autowired com.yadony.api.auth.UserRepository userRepository;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();

    /** Mêmes autorités que {@code AdminAuthService#buildAuthorities} en prod : permissions
     *  effectives du rôle mappées en authorité par nom, plus ROLE_ADMIN. Sans ça, un rôle ADMIN
     *  construit avec seulement ROLE_ADMIN échoue tous les {@code @PreAuthorize hasAuthority(...)}. */
    private static UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(
                ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-wallet-refund");
        List<GrantedAuthority> authorities = new java.util.ArrayList<>();
        for (AdminPermission p : AdminPermissions.effective(role, java.util.Map.of())) {
            authorities.add(new SimpleGrantedAuthority(p.name()));
        }
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private static UsernamePasswordAuthenticationToken nonAdminAuth() {
        return new UsernamePasswordAuthenticationToken("expediteur", null,
                List.of(new SimpleGrantedAuthority("ROLE_SENDER")));
    }

    private static WalletRefundRequestEntity pendingRequest(UUID id) {
        WalletRefundRequestEntity r = new WalletRefundRequestEntity();
        r.setUserId(USER_ID);
        r.setCurrency("CAD");
        r.setAmount(new BigDecimal("45.00"));
        r.setStatus(WalletRefundRequestStatus.PENDING);
        r.setRequestedAt(LocalDateTime.now());
        try {
            var idField = com.yadony.api.common.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(r, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return r;
    }

    // ── Permission ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET — sans ROLE_ADMIN → 403")
    void get_withoutAdminRole_returns403() throws Exception {
        mockMvc.perform(get("/admin/wallet-refund-requests").with(authentication(nonAdminAuth())))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("resolve — SUPPORT n'a pas PAYMENT_REFUND → 403, rien n'est débité")
    void resolve_withSupportRole_returns403() throws Exception {
        UUID requestId = UUID.randomUUID();
        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET — ADMIN → 200, la liste des demandes PENDING")
    void get_withAdmin_returnsPendingList() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(refundRequestRepository.findAllByStatusOrderByRequestedAtAsc(
                eq(WalletRefundRequestStatus.PENDING), any()))
                .thenReturn(new PageImpl<>(List.of(pendingRequest(requestId)),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/wallet-refund-requests").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].currency").value("CAD"))
                .andExpect(jsonPath("$.content[0].amount").value(45.00))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("GET ?status — suivi : remboursements automatiques d'un compte supprimé, avec nom et origine")
    void get_withStatuses_returnsAutomaticRefundsOfDeletedAccount() throws Exception {
        com.yadony.api.auth.UserEntity user = new com.yadony.api.auth.UserEntity();
        user.setFirebaseUid("uid-refund-" + UUID.randomUUID());
        user.setFirstName("Awa");
        user.setLastName("Diop");
        user.setStatus(com.yadony.api.auth.UserStatus.ACTIVE);
        user.setKycStatus(com.yadony.api.auth.KycStatus.PENDING);
        user.setRoles(new java.util.HashSet<>(java.util.Set.of(com.yadony.api.auth.Role.SENDER)));
        user.setTotalTrips(0);
        LocalDateTime deletedAt = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1);
        user.setDeletedAt(deletedAt);
        user = userRepository.saveAndFlush(user);

        WalletRefundRequestEntity automatic = pendingRequest(UUID.randomUUID());
        automatic.setUserId(user.getId());
        automatic.setChannel(com.yadony.api.payments.wallet.WalletRefundChannel.AUTOMATIC_STRIPE);
        automatic.setStatus(WalletRefundRequestStatus.REFUNDED);
        automatic.setRequestedAt(deletedAt.minusSeconds(3));
        when(refundRequestRepository.findAllByStatusIn(any(), any()))
                .thenReturn(new PageImpl<>(List.of(automatic),
                        org.springframework.data.domain.PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/admin/wallet-refund-requests")
                        .param("status", "PROCESSING,REFUNDED")
                        .param("status", "failed")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("REFUNDED"))
                .andExpect(jsonPath("$.content[0].channel").value("AUTOMATIC_STRIPE"))
                .andExpect(jsonPath("$.content[0].userName").value("Awa Diop"))
                .andExpect(jsonPath("$.content[0].origin").value("ACCOUNT_DELETION"));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<java.util.Collection<WalletRefundRequestStatus>> statuses =
                org.mockito.ArgumentCaptor.forClass(java.util.Collection.class);
        verify(refundRequestRepository).findAllByStatusIn(statuses.capture(), any());
        org.assertj.core.api.Assertions.assertThat(statuses.getValue()).containsExactlyInAnyOrder(
                WalletRefundRequestStatus.PROCESSING, WalletRefundRequestStatus.REFUNDED,
                WalletRefundRequestStatus.FAILED);
    }

    @Test
    @DisplayName("GET ?status inconnu → 400, aucune lecture")
    void get_withUnknownStatus_returns400() throws Exception {
        mockMvc.perform(get("/admin/wallet-refund-requests").param("status", "DONE")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/admin/wallet-refund-requests").param("status", " , ")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isBadRequest());
        verify(refundRequestRepository, never()).findAllByStatusIn(any(), any());
    }

    // ── resolve ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolve — ADMIN → 200, débite le solde réel et stampe resolvedBy = l'admin appelant")
    void resolve_withAdmin_debitsAndStampsResolver() throws Exception {
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestEntity request = pendingRequest(requestId);
        when(refundRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(walletService.getBalance(USER_ID, "CAD")).thenReturn(new BigDecimal("45.00"));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        // debitConfirmedRefund et non debit : ce dernier passe par assertNotFrozen, que le
        // ticket PENDING en cours de résolution déclenche lui-même.
        verify(walletService).debitConfirmedRefund(eq(USER_ID), eq("CAD"), eq(new BigDecimal("45.00")),
                eq(com.yadony.api.payments.wallet.WalletTransactionType.ADMIN_REFUND_OUT));
        verify(auditService).log(eq("wallet_refund_request"), any(), eq("RESOLVED"), eq(ADMIN_ID), any());
    }

    @Test
    @DisplayName("resolve — corps {paidAmount, note} → 200, montant versé et note renvoyés, débit inchangé")
    void resolve_withPaidAmountBody_returnsPaidAmountAndKeepsDebit() throws Exception {
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestEntity request = pendingRequest(requestId);
        when(refundRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(walletService.getBalance(USER_ID, "CAD")).thenReturn(new BigDecimal("45.00"));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidAmount\": 43.50, \"note\": \"Virement Interac, frais 1,50\"}")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.amount").value(45.00))
                .andExpect(jsonPath("$.paidAmount").value(43.50))
                .andExpect(jsonPath("$.resolutionNote").value("Virement Interac, frais 1,50"));

        verify(walletService).debitConfirmedRefund(eq(USER_ID), eq("CAD"), eq(new BigDecimal("45.00")),
                eq(com.yadony.api.payments.wallet.WalletTransactionType.ADMIN_REFUND_OUT));
    }

    @Test
    @DisplayName("resolve — Content-Type JSON mais corps vide → 200, comme sans corps")
    void resolve_withEmptyJsonBody_behavesAsBefore() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(refundRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(pendingRequest(requestId)));
        when(walletService.getBalance(USER_ID, "CAD")).thenReturn(new BigDecimal("45.00"));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.paidAmount").doesNotExist());
    }

    @Test
    @DisplayName("resolve — montant versé au-dessus de la demande → 422 problem+json, rien n'est débité")
    void resolve_paidAmountAboveRequest_returns422ProblemJson() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(refundRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(pendingRequest(requestId)));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidAmount\": 46}")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("wallet-refund-paid-amount-too-high"))
                .andExpect(jsonPath("$.maxAmount").value(45.00));

        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
    }

    @Test
    @DisplayName("resolve — trop de décimales ou montant négatif → 422 problem+json")
    void resolve_paidAmountInvalid_returns422ProblemJson() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(refundRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(pendingRequest(requestId)));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidAmount\": 44.505}")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("wallet-refund-paid-amount-scale"));
        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidAmount\": -1}")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("wallet-refund-paid-amount-invalid"));

        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
    }

    @Test
    @DisplayName("resolve avec corps — SUPPORT n'a pas PAYMENT_REFUND → 403, rien n'est lu ni débité")
    void resolve_withBody_withSupportRole_returns403() throws Exception {
        UUID requestId = UUID.randomUUID();
        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paidAmount\": 40}")
                        .with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verify(refundRequestRepository, never()).findById(any());
        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
    }

    @Test
    @DisplayName("resolve — demande introuvable → 404")
    void resolve_notFound_returns404() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(refundRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.empty());

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("resolve — déjà résolue → 422 already-resolved, rien n'est débité")
    void resolve_alreadyResolved_returns422() throws Exception {
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestEntity resolved = pendingRequest(requestId);
        resolved.setStatus(WalletRefundRequestStatus.RESOLVED);
        when(refundRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(resolved));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("already-resolved"));

        verify(walletService, never()).debitConfirmedRefund(any(), any(), any(), any());
    }

    // ── Relance du remboursement mobile money ─────────────────────────────────

    private static WalletRefundRetryService.Preview preview(UUID id) {
        return new WalletRefundRetryService.Preview(id, "XOF", "CI", "ORANGE_CIV", "+225 •••• 90",
                new BigDecimal("500"), new BigDecimal("477"), new BigDecimal("487.50"), new BigDecimal("473"),
                null, new BigDecimal("4"), new BigDecimal("3.0"), false, "pawapay-insufficient-funds");
    }

    @Test
    @DisplayName("retry-preview — ADMIN → 200 avec dû, solde, sûr, manque et numéro masqué (cas réel : relance fermée)")
    void retryPreview_withAdmin_returnsAmounts() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(walletRefundRetryService.preview(requestId)).thenReturn(preview(requestId));

        mockMvc.perform(get("/admin/wallet-refund-requests/" + requestId + "/retry-preview")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amountDue").value(477))
                .andExpect(jsonPath("$.walletBalance").value(487.50))
                .andExpect(jsonPath("$.maxSafeAmount").value(473))
                .andExpect(jsonPath("$.suggestedAmount").doesNotExist())
                .andExpect(jsonPath("$.topUpNeeded").value(4))
                .andExpect(jsonPath("$.unavailableReason").value("pawapay-insufficient-funds"))
                .andExpect(jsonPath("$.currency").value("XOF"))
                .andExpect(jsonPath("$.country").value("CI"))
                .andExpect(jsonPath("$.provider").value("ORANGE_CIV"))
                .andExpect(jsonPath("$.msisdnMasked").value("+225 •••• 90"))
                .andExpect(jsonPath("$.retryAvailable").value(false));
    }

    @Test
    @DisplayName("retry-preview — demande non éligible → 422 problem+json avec le motif")
    void retryPreview_notEligible_returns422Problem() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(walletRefundRetryService.preview(requestId)).thenThrow(new YadonyBusinessException(
                HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-retry-not-eligible", "Unprocessable",
                "Cette demande ne vient pas d'une recharge mobile money", java.util.Map.of("reason",
                        "not-mobile-money-topup")));

        mockMvc.perform(get("/admin/wallet-refund-requests/" + requestId + "/retry-preview")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("wallet-refund-retry-not-eligible"))
                .andExpect(jsonPath("$.reason").value("not-mobile-money-topup"));
    }

    @Test
    @DisplayName("retry-preview et retry — SUPPORT n'a pas PAYMENT_REFUND → 403, rien n'est envoyé")
    void retry_withSupportRole_returns403() throws Exception {
        UUID requestId = UUID.randomUUID();
        mockMvc.perform(get("/admin/wallet-refund-requests/" + requestId + "/retry-preview")
                        .with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/retry")
                        .with(authentication(adminAuth(AdminRole.SUPPORT)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 473}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/retry")
                        .with(authentication(nonAdminAuth())))
                .andExpect(status().isForbidden());

        verify(walletRefundRetryService, never()).preview(any());
        verify(walletRefundRetryService, never()).retry(any(), any(), any());
    }

    @Test
    @DisplayName("retry — ADMIN, montant choisi → 200, demande PROCESSING et chiffres retenus")
    void retry_withAmount_returnsProcessing() throws Exception {
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestEntity processing = pendingRequest(requestId);
        processing.setStatus(WalletRefundRequestStatus.PROCESSING);
        when(walletRefundRetryService.retry(eq(requestId), any(), eq(ADMIN_ID)))
                .thenReturn(new WalletRefundRetryService.Retried(processing, new BigDecimal("477"),
                        new BigDecimal("477"), new BigDecimal("491.50"), new BigDecimal("477")));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/retry")
                        .with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 477}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.status").value("PROCESSING"))
                .andExpect(jsonPath("$.amount").value(477))
                .andExpect(jsonPath("$.maxSafeAmount").value(477));

        verify(walletRefundRetryService).retry(eq(requestId),
                org.mockito.ArgumentMatchers.argThat(a -> a != null && a.compareTo(new BigDecimal("477")) == 0),
                eq(ADMIN_ID));
    }

    @Test
    @DisplayName("retry — sans corps → le dû (null transmis au service)")
    void retry_withoutBody_usesSuggestedAmount() throws Exception {
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestEntity processing = pendingRequest(requestId);
        processing.setStatus(WalletRefundRequestStatus.PROCESSING);
        when(walletRefundRetryService.retry(requestId, null, ADMIN_ID))
                .thenReturn(new WalletRefundRetryService.Retried(processing, new BigDecimal("477"),
                        new BigDecimal("477"), new BigDecimal("491.50"), new BigDecimal("477")));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/retry")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(477));
        verify(walletRefundRetryService).retry(requestId, null, ADMIN_ID);
    }

    @Test
    @DisplayName("retry — fonds pawaPay insuffisants → 422 problem+json avec maxSafeAmount et topUpNeeded")
    void retry_insufficientFunds_returns422Problem() throws Exception {
        UUID requestId = UUID.randomUUID();
        java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
        props.put("maxSafeAmount", new BigDecimal("473"));
        props.put("topUpNeeded", new BigDecimal("4"));
        when(walletRefundRetryService.retry(eq(requestId), any(), eq(ADMIN_ID))).thenThrow(
                new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY, "wallet-refund-insufficient-pawapay-funds",
                        "Unprocessable", "Le wallet pawaPay CI XOF ne permet de verser au plus que 473 XOF", props));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/retry")
                        .with(authentication(adminAuth(AdminRole.ADMIN)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 477}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("wallet-refund-insufficient-pawapay-funds"))
                .andExpect(jsonPath("$.maxSafeAmount").value(473))
                .andExpect(jsonPath("$.topUpNeeded").value(4));
    }
}
