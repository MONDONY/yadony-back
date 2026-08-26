package com.yadony.api.admin;

import com.yadony.api.auth.AccountFinalizationService;
import com.yadony.api.auth.FinalizationReason;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserDeletionServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Mock UserRepository userRepository;
    @Mock UserDeletionImpactService impactService;
    @Mock AccountFinalizationService finalizationService;
    @Mock AuditService auditService;

    private AdminUserDeletionService service() {
        return new AdminUserDeletionService(
                userRepository, impactService, finalizationService, auditService);
    }

    private UserEntity existingUser() {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        return u;
    }

    @Test
    @DisplayName("sans blocage, le compte est anonymisé au motif d'une décision administrateur")
    void noBlocking_finalizesAccount() {
        UserEntity user = existingUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(impactService.hasBlocking(USER_ID)).thenReturn(false);

        service().delete(USER_ID, ADMIN_ID, "FRAUD", "faux documents");

        verify(finalizationService).finalize(user, FinalizationReason.ADMIN_INITIATED);
        verify(auditService).log(
                eq("USER"), eq(USER_ID), eq("USER_ADMIN_DELETION"), eq(ADMIN_ID), anyMap());
    }

    // Le rapport affiché a pu vieillir de plusieurs minutes ; un escrow peut s'être ouvert
    // entre l'affichage et le clic. Le front ne fait jamais autorité.
    @Test
    @DisplayName("un blocage apparu depuis l'affichage refuse l'exécution en 422")
    void blockingAppearedSinceReport_refuses() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser()));
        when(impactService.hasBlocking(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service().delete(USER_ID, ADMIN_ID, "FRAUD", "faux documents"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        verify(finalizationService, never()).finalize(any(), any());
    }

    @Test
    @DisplayName("un compte introuvable donne 404 sans rien exécuter")
    void unknownUser_returns404() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(USER_ID, ADMIN_ID, "FRAUD", "faux documents"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(finalizationService, never()).finalize(any(), any());
        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }

    @Test
    @DisplayName("l'administrateur est l'acteur de l'entrée d'audit, pas le compte supprimé")
    void audit_recordsAdminAsActor() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser()));
        when(impactService.hasBlocking(USER_ID)).thenReturn(false);

        service().delete(USER_ID, ADMIN_ID, "ABUSE", "spam massif");

        verify(auditService).log(
                eq("USER"), eq(USER_ID), eq("USER_ADMIN_DELETION"), eq(ADMIN_ID), anyMap());
    }

    @Test
    @DisplayName("aucune entrée d'audit n'est écrite quand la suppression est refusée")
    void refusedDeletion_writesNoAudit() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser()));
        when(impactService.hasBlocking(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> service().delete(USER_ID, ADMIN_ID, "FRAUD", "x"))
                .isInstanceOf(YadonyBusinessException.class);

        verify(auditService, never()).log(any(), any(), any(), any(), anyMap());
    }
}
