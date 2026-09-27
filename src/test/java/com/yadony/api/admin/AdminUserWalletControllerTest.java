package com.yadony.api.admin;

import com.yadony.api.admin.account.AdminPermission;
import com.yadony.api.admin.account.AdminPermissions;
import com.yadony.api.admin.account.AdminPrincipal;
import com.yadony.api.admin.account.AdminRole;
import com.yadony.api.admin.account.AdminUserEntity;
import com.yadony.api.admin.account.AdminUserRepository;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.wallet.WalletAccountView;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentCommand;
import com.yadony.api.payments.wallet.WalletAdminAdjustmentService.AdjustmentResult;
import com.yadony.api.payments.wallet.WalletTransactionEntity;
import com.yadony.api.payments.wallet.WalletTransactionType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
@DisplayName("AdminUserWalletControllerTest — /admin/users/{userId}/wallet")
class AdminUserWalletControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean WalletAdminAdjustmentService adjustmentService;
    @MockitoBean UserRepository userRepository;
    @MockitoBean AdminUserRepository adminUserRepository;

    private static final UUID ADMIN_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final String BASE = "/admin/users/" + USER_ID + "/wallet";
    private static final String BODY = """
            {"currency":"XOF","direction":"CREDIT","amount":1500,"reason":"Geste commercial après litige"}
            """;

    private static UsernamePasswordAuthenticationToken adminAuth(AdminRole role) {
        AdminPrincipal principal = new AdminPrincipal(ADMIN_ID, "admin@yadony.test", role, false, "uid-admin-wallet");
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (AdminPermission p : AdminPermissions.effective(role, Map.of())) {
            authorities.add(new SimpleGrantedAuthority(p.name()));
        }
        authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    /** WALLET_ADJUST sans AdminPrincipal : l'annotation passe, requireAdminId doit refuser. */
    private static UsernamePasswordAuthenticationToken authorityWithoutPrincipal() {
        return new UsernamePasswordAuthenticationToken("not-an-admin-principal", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("WALLET_ADJUST")));
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

    private static WalletTransactionEntity adjustmentTx(UUID id) {
        WalletTransactionEntity tx = withId(new WalletTransactionEntity(), id);
        tx.setUserId(USER_ID);
        tx.setCurrency("XOF");
        tx.setType(WalletTransactionType.ADMIN_CREDIT);
        tx.setAmount(new BigDecimal("1500.00"));
        tx.setBalanceAfter(new BigDecimal("4500.00"));
        tx.setAdminReason("Geste commercial après litige");
        tx.setAdminActorId(ADMIN_ID);
        return tx;
    }

    private static WalletTransactionEntity topupTx(UUID id) {
        WalletTransactionEntity tx = withId(new WalletTransactionEntity(), id);
        tx.setUserId(USER_ID);
        tx.setCurrency("EUR");
        tx.setType(WalletTransactionType.TOP_UP);
        tx.setAmount(new BigDecimal("12.50"));
        tx.setBalanceAfter(new BigDecimal("12.50"));
        tx.setPaymentRef("pi_123");
        return tx;
    }

    @BeforeEach
    void existingUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(new UserEntity()));
        AdminUserEntity admin = withId(new AdminUserEntity(), ADMIN_ID);
        admin.setEmail("ops@yadony.test");
        when(adminUserRepository.findAllById(any())).thenReturn(List.of(admin));
    }

    // ── GET /wallet ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET wallet — SUPPORT (PAYMENT_VIEW) → 200, forme exacte du contrat")
    void getWallet_support_returnsAccounts() throws Exception {
        when(adjustmentService.accounts(USER_ID)).thenReturn(List.of(
                new WalletAccountView("EUR", new BigDecimal("12.50"), new BigDecimal("10.00"), false)));

        mockMvc.perform(get(BASE).with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(1))
                .andExpect(jsonPath("$.accounts[0].currency").value("EUR"))
                .andExpect(jsonPath("$.accounts[0].balance").value(12.50))
                .andExpect(jsonPath("$.accounts[0].refundEligibleAmount").value(10.00))
                .andExpect(jsonPath("$.accounts[0].frozen").value(false));
    }

    @Test
    @DisplayName("GET wallet — aucun compte → liste vide")
    void getWallet_noAccount_returnsEmptyList() throws Exception {
        when(adjustmentService.accounts(USER_ID)).thenReturn(List.of());

        mockMvc.perform(get(BASE).with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.length()").value(0));
    }

    @Test
    @DisplayName("GET wallet — utilisateur inexistant → 404")
    void getWallet_unknownUser_returns404() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE).with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));
    }

    @Test
    @DisplayName("GET wallet — utilisateur supprimé (deleted_at) → 404")
    void getWallet_deletedUser_returns404() throws Exception {
        UserEntity deleted = new UserEntity();
        deleted.setDeletedAt(LocalDateTime.now());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(deleted));

        mockMvc.perform(get(BASE).with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET wallet — non admin → 403")
    void getWallet_nonAdmin_returns403() throws Exception {
        mockMvc.perform(get(BASE).with(authentication(new UsernamePasswordAuthenticationToken("u", null,
                        List.of(new SimpleGrantedAuthority("ROLE_SENDER"), new SimpleGrantedAuthority("PAYMENT_VIEW"))))))
                .andExpect(status().isForbidden());
    }

    // ── GET /wallet/transactions ─────────────────────────────────────────────

    @Test
    @DisplayName("GET transactions — SUPPORT → 200, page Spring et champs admin résolus")
    void getTransactions_support_returnsPage() throws Exception {
        UUID adjustId = UUID.randomUUID();
        UUID topupId = UUID.randomUUID();
        when(adjustmentService.transactions(USER_ID, "XOF", "ADMIN_CREDIT", 1, 5)).thenReturn(
                new PageImpl<>(List.of(adjustmentTx(adjustId), topupTx(topupId)), PageRequest.of(1, 5), 7));

        mockMvc.perform(get(BASE + "/transactions")
                        .param("currency", "XOF").param("type", "ADMIN_CREDIT")
                        .param("page", "1").param("size", "5")
                        .with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.content[0].id").value(adjustId.toString()))
                .andExpect(jsonPath("$.content[0].currency").value("XOF"))
                .andExpect(jsonPath("$.content[0].type").value("ADMIN_CREDIT"))
                .andExpect(jsonPath("$.content[0].amount").value(1500.00))
                .andExpect(jsonPath("$.content[0].balanceAfter").value(4500.00))
                .andExpect(jsonPath("$.content[0].bidId").value(nullValue()))
                .andExpect(jsonPath("$.content[0].paymentRef").value(nullValue()))
                .andExpect(jsonPath("$.content[0].createdAt").hasJsonPath())
                .andExpect(jsonPath("$.content[0].adminReason").value("Geste commercial après litige"))
                .andExpect(jsonPath("$.content[0].adminActorId").value(ADMIN_ID.toString()))
                .andExpect(jsonPath("$.content[0].adminActorEmail").value("ops@yadony.test"))
                .andExpect(jsonPath("$.content[1].paymentRef").value("pi_123"))
                .andExpect(jsonPath("$.content[1].adminReason").value(nullValue()))
                .andExpect(jsonPath("$.content[1].adminActorId").value(nullValue()))
                .andExpect(jsonPath("$.content[1].adminActorEmail").value(nullValue()));
    }

    @Test
    @DisplayName("GET transactions — valeurs par défaut page=0 size=20")
    void getTransactions_defaults() throws Exception {
        when(adjustmentService.transactions(USER_ID, null, null, 0, 20)).thenReturn(Page0.empty());

        mockMvc.perform(get(BASE + "/transactions").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        verify(adjustmentService).transactions(USER_ID, null, null, 0, 20);
    }

    @Test
    @DisplayName("GET transactions — type inconnu → 400 ProblemDetail")
    void getTransactions_unknownType_returns400() throws Exception {
        when(adjustmentService.transactions(eq(USER_ID), any(), eq("BOGUS"), anyInt(), anyInt())).thenThrow(
                new YadonyBusinessException(HttpStatus.BAD_REQUEST, "wallet-transaction-type-invalid",
                        "Bad Request", "Type de mouvement inconnu"));

        mockMvc.perform(get(BASE + "/transactions").param("type", "BOGUS")
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("wallet-transaction-type-invalid"));
    }

    @Test
    @DisplayName("GET transactions — utilisateur inexistant → 404")
    void getTransactions_unknownUser_returns404() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(get(BASE + "/transactions").with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound());
    }

    // ── POST /wallet/adjustments ─────────────────────────────────────────────

    @Test
    @DisplayName("POST — ADMIN → 200, { account, transaction } et commande transmise telle quelle")
    void adjust_admin_returnsAccountAndTransaction() throws Exception {
        UUID txId = UUID.randomUUID();
        when(adjustmentService.adjust(any())).thenReturn(new AdjustmentResult(adjustmentTx(txId), false));
        when(adjustmentService.account(USER_ID, "XOF")).thenReturn(
                new WalletAccountView("XOF", new BigDecimal("4500.00"), BigDecimal.ZERO, false));

        mockMvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "k-123")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.currency").value("XOF"))
                .andExpect(jsonPath("$.account.balance").value(4500.00))
                .andExpect(jsonPath("$.account.refundEligibleAmount").value(0))
                .andExpect(jsonPath("$.account.frozen").value(false))
                .andExpect(jsonPath("$.transaction.id").value(txId.toString()))
                .andExpect(jsonPath("$.transaction.type").value("ADMIN_CREDIT"))
                .andExpect(jsonPath("$.transaction.amount").value(1500.00))
                .andExpect(jsonPath("$.transaction.adminReason").value("Geste commercial après litige"))
                .andExpect(jsonPath("$.transaction.adminActorEmail").value("ops@yadony.test"));

        ArgumentCaptor<AdjustmentCommand> command = ArgumentCaptor.forClass(AdjustmentCommand.class);
        verify(adjustmentService).adjust(command.capture());
        assertThat(command.getValue().userId()).isEqualTo(USER_ID);
        assertThat(command.getValue().adminId()).isEqualTo(ADMIN_ID);
        assertThat(command.getValue().currency()).isEqualTo("XOF");
        assertThat(command.getValue().direction()).isEqualTo("CREDIT");
        assertThat(command.getValue().amount()).isEqualByComparingTo("1500");
        assertThat(command.getValue().reason()).isEqualTo("Geste commercial après litige");
        assertThat(command.getValue().idempotencyKey()).isEqualTo("k-123");
    }

    @Test
    @DisplayName("POST — SUPER_ADMIN → 200")
    void adjust_superAdmin_allowed() throws Exception {
        when(adjustmentService.adjust(any())).thenReturn(new AdjustmentResult(adjustmentTx(UUID.randomUUID()), true));
        when(adjustmentService.account(USER_ID, "XOF")).thenReturn(
                new WalletAccountView("XOF", new BigDecimal("4500.00"), null, false));

        mockMvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(adminAuth(AdminRole.SUPER_ADMIN))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.account.refundEligibleAmount").value(nullValue()));
    }

    @Test
    @DisplayName("POST — SUPPORT (sans WALLET_ADJUST) → 403, rien n'est écrit")
    void adjust_support_returns403() throws Exception {
        mockMvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(adminAuth(AdminRole.SUPPORT))))
                .andExpect(status().isForbidden());

        verify(adjustmentService, never()).adjust(any());
    }

    @Test
    @DisplayName("POST — WALLET_ADJUST sans ROLE_ADMIN → 403")
    void adjust_authorityWithoutAdminRole_returns403() throws Exception {
        mockMvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(new UsernamePasswordAuthenticationToken("u", null,
                                List.of(new SimpleGrantedAuthority("WALLET_ADJUST"))))))
                .andExpect(status().isForbidden());

        verify(adjustmentService, never()).adjust(any());
    }

    @Test
    @DisplayName("POST — pas d'AdminPrincipal → 403 admin-principal-required")
    void adjust_withoutAdminPrincipal_returns403() throws Exception {
        mockMvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(authorityWithoutPrincipal())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("admin-principal-required"));

        verify(adjustmentService, never()).adjust(any());
    }

    @Test
    @DisplayName("POST — en-tête Idempotency-Key absent ou vide → 400 idempotency-key-required")
    void adjust_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post(BASE + "/adjustments")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("idempotency-key-required"));
        mockMvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "  ")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("idempotency-key-required"));

        verify(adjustmentService, never()).adjust(any());
    }

    @Test
    @DisplayName("POST — utilisateur inexistant → 404, rien n'est écrit")
    void adjust_unknownUser_returns404() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        mockMvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("user-not-found"));

        verify(adjustmentService, never()).adjust(any());
    }

    @Test
    @DisplayName("POST — erreur métier du service → ProblemDetail relayé (409 conflit d'idempotence)")
    void adjust_conflict_returns409ProblemDetail() throws Exception {
        when(adjustmentService.adjust(any())).thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                "wallet-adjustment-idempotency-conflict", "Conflict", "Clé déjà utilisée"));

        mockMvc.perform(post(BASE + "/adjustments").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .with(authentication(adminAuth(AdminRole.ADMIN))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("wallet-adjustment-idempotency-conflict"));
        verify(adjustmentService, never()).account(any(), anyString());
    }

    /** Page vide typée, sans dépendre de Page.empty() qui sérialise un Pageable non paginé. */
    private static final class Page0 {
        static PageImpl<WalletTransactionEntity> empty() {
            return new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
        }
    }
}
