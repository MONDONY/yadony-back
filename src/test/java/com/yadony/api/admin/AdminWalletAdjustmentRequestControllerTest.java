package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPermissions;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletAccountView;
import com.yadony.api.payments.wallet.WalletAdjustmentDirection;
import com.yadony.api.payments.wallet.WalletAdjustmentRequestEntity;
import com.yadony.api.payments.wallet.WalletAdjustmentRequestStatus;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentResult;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
@DisplayName("AdminWalletAdjustmentRequestControllerTest — /admin/wallet-adjustment-requests")
class AdminWalletAdjustmentRequestControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean WalletAdminAdjustmentService adjustmentService;
    @MockitoBean AdminUserRepository adminUserRepository;

    private static final String BASE = "/admin/wallet-adjustment-requests";
    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID AUTHOR_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID TX_ID = UUID.randomUUID();

    private static UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "approver@yadony.test", role, false, "uid-approver");
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (AdminPermission p : AdminPermissions.effective(role, Map.of())) {
            authorities.add(new SimpleGrantedAuthority(p.name()));
        }
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    private static <T> T withId(T entity, UUID id) {
        try {
            Class<?> type = entity.getClass();
            Field f = null;
            while (f == null && type != null) {
                try {
                    f = type.getDeclaredField("id");
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
            f.setAccessible(true);
            f.set(entity, id);
            return entity;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static AdminUserEntity admin(UUID id, String email) {
        AdminUserEntity admin = withId(new AdminUserEntity(), id);
        admin.setEmail(email);
        return admin;
    }

    private static WalletAdjustmentRequestEntity request(WalletAdjustmentRequestStatus status) {
        WalletAdjustmentRequestEntity r = withId(new WalletAdjustmentRequestEntity(), REQUEST_ID);
        r.setUserId(USER_ID);
        r.setCurrency("EUR");
        r.setDirection(WalletAdjustmentDirection.CREDIT);
        r.setAmount(new BigDecimal("150.00"));
        r.setEurEquivalent(new BigDecimal("150.00"));
        r.setReason("Geste commercial après litige");
        r.setIdempotencyKey("admin-adjust:k-1");
        r.setRequestedBy(AUTHOR_ID);
        r.setStatus(status);
        if (status != WalletAdjustmentRequestStatus.PENDING) {
            r.setDecidedBy(ADMIN_ID);
            r.setDecidedAt(LocalDateTime.of(2026, 10, 28, 12, 0));
        }
        return r;
    }

    @BeforeEach
    void admins() {
        when(adminUserRepository.findAllById(any())).thenReturn(List.of(
                admin(AUTHOR_ID, "author@yadony.test"), admin(ADMIN_ID, "approver@yadony.test")));
    }

    @Test
    @DisplayName("GET — file PENDING par défaut, e-mails de l'auteur résolus")
    void list_defaults_pendingWithAuthorEmail() throws Exception {
        when(adjustmentService.requests(WalletAdjustmentRequestStatus.PENDING, 0, 20))
                .thenReturn(new PageImpl<>(List.of(request(WalletAdjustmentRequestStatus.PENDING)),
                        PageRequest.of(0, 20), 1));

        mockMvc.perform(get(BASE).with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(REQUEST_ID.toString()))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$.content[0].requestedByEmail").value("author@yadony.test"))
                .andExpect(jsonPath("$.content[0].decidedBy").value(nullValue()));
    }

    @Test
    @DisplayName("GET — statut en minuscules accepté, statut inconnu → 400")
    void list_statusParsing() throws Exception {
        when(adjustmentService.requests(eq(WalletAdjustmentRequestStatus.REJECTED), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get(BASE).param("status", " rejected ").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk());
        mockMvc.perform(get(BASE).param("status", "DONE").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("wallet-adjustment-request-status-invalid"));
    }

    @Test
    @DisplayName("Sans WALLET_ADJUST (SUPPORT) → 403 sur la file, l'approbation et le rejet")
    void support_forbidden() throws Exception {
        mockMvc.perform(get(BASE).with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/" + REQUEST_ID + "/approve").with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE + "/" + REQUEST_ID + "/reject").with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());
        verify(adjustmentService, never()).approve(any(), any());
    }

    @Test
    @DisplayName("POST approve — approbateur = admin connecté, compte, mouvement et demande renvoyés")
    void approve_returnsAccountTransactionAndRequest() throws Exception {
        WalletTransactionEntity tx = withId(new WalletTransactionEntity(), TX_ID);
        tx.setUserId(USER_ID);
        tx.setCurrency("EUR");
        tx.setType(WalletTransactionType.ADMIN_CREDIT);
        tx.setAmount(new BigDecimal("150.00"));
        tx.setBalanceAfter(new BigDecimal("150.00"));
        tx.setAdminActorId(AUTHOR_ID);
        WalletAdjustmentRequestEntity decided = request(WalletAdjustmentRequestStatus.APPROVED);
        decided.setTransactionId(TX_ID);
        when(adjustmentService.approve(REQUEST_ID, ADMIN_ID)).thenReturn(new AdjustmentResult(tx, false, decided));
        when(adjustmentService.account(USER_ID, "EUR")).thenReturn(
                new WalletAccountView("EUR", new BigDecimal("150.00"), BigDecimal.ZERO, false));

        mockMvc.perform(post(BASE + "/" + REQUEST_ID + "/approve").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.balance").value(150.00))
                .andExpect(jsonPath("$.transaction.id").value(TX_ID.toString()))
                .andExpect(jsonPath("$.transaction.adminActorEmail").value("author@yadony.test"))
                .andExpect(jsonPath("$.pendingRequest.status").value("APPROVED"))
                .andExpect(jsonPath("$.pendingRequest.decidedByEmail").value("approver@yadony.test"))
                .andExpect(jsonPath("$.pendingRequest.transactionId").value(TX_ID.toString()));
    }

    @Test
    @DisplayName("POST approve — compte introuvable : account null")
    void approve_withoutAccount_nullAccount() throws Exception {
        WalletTransactionEntity tx = withId(new WalletTransactionEntity(), TX_ID);
        tx.setUserId(USER_ID);
        tx.setCurrency("EUR");
        tx.setType(WalletTransactionType.ADMIN_CREDIT);
        tx.setAmount(new BigDecimal("150.00"));
        tx.setBalanceAfter(new BigDecimal("150.00"));
        when(adjustmentService.approve(REQUEST_ID, ADMIN_ID))
                .thenReturn(new AdjustmentResult(tx, false, request(WalletAdjustmentRequestStatus.APPROVED)));

        mockMvc.perform(post(BASE + "/" + REQUEST_ID + "/approve").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account").value(nullValue()));
    }

    @Test
    @DisplayName("POST approve — auto-approbation refusée par le service → 403 ProblemDetail")
    void approve_selfApproval_403() throws Exception {
        when(adjustmentService.approve(REQUEST_ID, ADMIN_ID)).thenThrow(new YadonyBusinessException(
                HttpStatus.FORBIDDEN, "wallet-adjustment-self-approval", "Forbidden", "Autre admin requis"));

        mockMvc.perform(post(BASE + "/" + REQUEST_ID + "/approve").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("wallet-adjustment-self-approval"));
    }

    @Test
    @DisplayName("POST reject — note transmise, demande rejetée renvoyée")
    void reject_withNote() throws Exception {
        WalletAdjustmentRequestEntity rejected = request(WalletAdjustmentRequestStatus.REJECTED);
        rejected.setDecisionNote("Montant erroné");
        when(adjustmentService.reject(REQUEST_ID, ADMIN_ID, "Montant erroné")).thenReturn(rejected);

        mockMvc.perform(post(BASE + "/" + REQUEST_ID + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Montant erroné\"}")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decisionNote").value("Montant erroné"))
                .andExpect(jsonPath("$.decidedByEmail").value("approver@yadony.test"));
    }

    @Test
    @DisplayName("POST reject — sans corps : note nulle")
    void reject_withoutBody() throws Exception {
        when(adjustmentService.reject(eq(REQUEST_ID), eq(ADMIN_ID), isNull()))
                .thenReturn(request(WalletAdjustmentRequestStatus.REJECTED));

        mockMvc.perform(post(BASE + "/" + REQUEST_ID + "/reject").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisionNote").value(nullValue()));
    }
}
