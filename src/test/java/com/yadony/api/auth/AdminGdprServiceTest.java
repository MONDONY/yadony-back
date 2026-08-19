package com.yadony.api.auth;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminGdprService — file des demandes RGPD et exécution par un administrateur")
class AdminGdprServiceTest {

    @Mock UserRepository userRepository;
    @Mock UserService userService;
    @Mock AccountFinalizationService accountFinalizationService;
    @Mock AuditService auditService;

    AdminGdprService service;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminGdprService(userRepository, userService, accountFinalizationService, auditService);
    }

    private UserEntity buildPendingUser() {
        UserEntity u = new UserEntity();
        setId(u, UUID.randomUUID());
        u.setFirebaseUid("uid-001");
        u.setFirstName("Jean");
        u.setLastName("Dupont");
        u.setStatus(UserStatus.PENDING_DELETION);
        u.setDeletionRequestedAt(Instant.parse("2026-07-01T00:00:00Z"));
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ── listDeletionRequests ──────────────────────────────────────────────────

    @Test
    @DisplayName("file : les demandes les plus anciennes d'abord, comptes finalisés exclus")
    void listDeletionRequests_delegatesToRepositoryOrderedByAge() {
        UserEntity user = buildPendingUser();
        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findByDeletionRequestedAtIsNotNullOrderByDeletionRequestedAtAsc(pageable))
                .thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        Page<UserEntity> page = service.listDeletionRequests(pageable);

        assertThat(page.getContent()).containsExactly(user);
        // @Where(deleted_at IS NULL) sur UserEntity exclut déjà les comptes finalisés :
        // la requête dérivée en hérite, aucun filtre supplémentaire n'est nécessaire.
        verify(userRepository).findByDeletionRequestedAtIsNotNullOrderByDeletionRequestedAtAsc(pageable);
    }

    // ── executeDeletion ───────────────────────────────────────────────────────

    @Test
    @DisplayName("exécution : finalize appelé avec ADMIN_INITIATED, pas deleteImmediately")
    void executeDeletion_callsFinalizeWithAdminInitiated() {
        UserEntity user = buildPendingUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userService.hasActiveEscrow(user.getId())).thenReturn(false);

        service.executeDeletion(user.getId(), ADMIN_ID, "demande utilisateur confirmée", false);

        verify(accountFinalizationService).finalize(user, FinalizationReason.ADMIN_INITIATED);
    }

    @Test
    @DisplayName("exécution : audit USER_GDPR_EXECUTED avec l'admin comme acteur")
    void executeDeletion_auditsWithAdminAsActor() {
        UserEntity user = buildPendingUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userService.hasActiveEscrow(user.getId())).thenReturn(false);

        service.executeDeletion(user.getId(), ADMIN_ID, "demande utilisateur confirmée", false);

        verify(auditService).log(eq("USER"), eq(user.getId()), eq("USER_GDPR_EXECUTED"),
                eq(ADMIN_ID), any());
    }

    @Test
    @DisplayName("exécution : escrow actif → 422 active-transactions, rien n'est finalisé")
    void executeDeletion_activeEscrow_throws422() {
        UserEntity user = buildPendingUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userService.hasActiveEscrow(user.getId())).thenReturn(true);

        assertThatThrownBy(() -> service.executeDeletion(user.getId(), ADMIN_ID, "motif", false))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException y = (YadonyBusinessException) e;
                    assertThat(y.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(y.getErrorCode()).isEqualTo("active-transactions");
                });

        verifyNoInteractions(accountFinalizationService);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("exécution : solde wallet non vide → 422 wallet-balance-not-empty via UserService")
    void executeDeletion_walletBalance_throws422() {
        UserEntity user = buildPendingUser();
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(userService.hasActiveEscrow(user.getId())).thenReturn(false);
        doThrow(new YadonyBusinessException(HttpStatus.UNPROCESSABLE_ENTITY,
                "wallet-balance-not-empty", "Unprocessable", "Solde wallet non nul"))
                .when(userService).assertNoWalletBalance(user.getId());

        assertThatThrownBy(() -> service.executeDeletion(user.getId(), ADMIN_ID, "motif", false))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("wallet-balance-not-empty"));

        verifyNoInteractions(accountFinalizationService);
    }

    @Test
    @DisplayName("exécution : utilisateur inconnu ou déjà finalisé → 404 user-not-found")
    void executeDeletion_unknownOrAlreadyFinalized_throws404() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.executeDeletion(unknown, ADMIN_ID, "motif", false))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException y = (YadonyBusinessException) e;
                    assertThat(y.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(y.getErrorCode()).isEqualTo("user-not-found");
                });
    }
}
