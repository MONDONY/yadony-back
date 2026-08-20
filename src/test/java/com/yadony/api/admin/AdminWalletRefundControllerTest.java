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
import com.yadony.api.payments.wallet.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageImpl;
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

        verify(walletService, never()).debit(any(), any(), any(), any(), any());
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

    // ── resolve ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("resolve — ADMIN → 200, débite le solde réel et stampe resolvedBy = l'admin appelant")
    void resolve_withAdmin_debitsAndStampsResolver() throws Exception {
        UUID requestId = UUID.randomUUID();
        WalletRefundRequestEntity request = pendingRequest(requestId);
        when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(request));
        when(walletService.getBalance(USER_ID, "CAD")).thenReturn(new BigDecimal("45.00"));
        when(refundRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        verify(walletService).debit(eq(USER_ID), eq("CAD"), eq(new BigDecimal("45.00")),
                eq(com.yadony.api.payments.wallet.WalletTransactionType.ADMIN_REFUND_OUT), eq(null));
        verify(auditService).log(eq("wallet_refund_request"), any(), eq("RESOLVED"), eq(ADMIN_ID), any());
    }

    @Test
    @DisplayName("resolve — demande introuvable → 404")
    void resolve_notFound_returns404() throws Exception {
        UUID requestId = UUID.randomUUID();
        when(refundRequestRepository.findById(requestId)).thenReturn(Optional.empty());

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
        when(refundRequestRepository.findById(requestId)).thenReturn(Optional.of(resolved));

        mockMvc.perform(post("/admin/wallet-refund-requests/" + requestId + "/resolve")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("already-resolved"));

        verify(walletService, never()).debit(any(), any(), any(), any(), any());
    }
}
