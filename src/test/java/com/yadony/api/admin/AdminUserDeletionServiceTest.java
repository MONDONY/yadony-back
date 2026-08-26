package com.yadony.api.admin;

import com.yadony.api.admin.dto.DeletionImpactResponse;
import com.yadony.api.auth.AccountFinalizationService;
import com.yadony.api.auth.FinalizationReason;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.common.deletion.ImpactSeverity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
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

    private DeletionImpactResponse emptyReport() {
        return new DeletionImpactResponse(false, List.of());
    }

    @Test
    @DisplayName("sans blocage, le compte est anonymisé au motif d'une décision administrateur")
    void noBlocking_finalizesAccount() {
        UserEntity user = existingUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(impactService.hasBlocking(USER_ID)).thenReturn(false);
        when(impactService.report(USER_ID)).thenReturn(emptyReport());

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
        when(impactService.report(USER_ID)).thenReturn(emptyReport());

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

    // Constat 2 : le motif libre ne doit jamais atterrir dans audit_log (table immuable).
    // Un administrateur pourrait y écrire un nom, un email ou un numéro de téléphone —
    // données impossibles à rectifier après coup.
    @Test
    @DisplayName("le motif libre contenant des données personnelles ne figure pas dans le payload d'audit")
    void auditPayload_doesNotContainFreeTextReason() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser()));
        when(impactService.hasBlocking(USER_ID)).thenReturn(false);
        when(impactService.report(USER_ID)).thenReturn(emptyReport());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(Map.class);

        service().delete(USER_ID, ADMIN_ID, "FRAUD", "Jean Dupont, jean@example.com, +33612345678");

        verify(auditService).log(
                eq("USER"), eq(USER_ID), eq("USER_ADMIN_DELETION"), eq(ADMIN_ID),
                payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        // La clé "reason" (motif libre) ne doit pas être présente.
        assertThat(payload).doesNotContainKey("reason");
        // Le motif catalogué (enum, sans PII) doit rester.
        assertThat(payload).containsEntry("reasonCode", "FRAUD");
    }

    // Constat 3 : l'instantané des décomptes par constat doit figurer dans le payload.
    // Ces décomptes sont la seule trace de l'état du compte au moment de la suppression.
    @Test
    @DisplayName("le payload d'audit contient l'instantané des décomptes par code de constat")
    void auditPayload_containsImpactSnapshot() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existingUser()));
        when(impactService.hasBlocking(USER_ID)).thenReturn(false);

        DeletionImpactResponse reportWithFindings = new DeletionImpactResponse(false, List.of(
                new DeletionImpactResponse.Finding(ImpactSeverity.WARNING.name(), "OPEN_DISPUTE", 2, List.of()),
                new DeletionImpactResponse.Finding(ImpactSeverity.INFO.name(), "RATINGS_GIVEN", 5, List.of())
        ));
        when(impactService.report(USER_ID)).thenReturn(reportWithFindings);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(Map.class);

        service().delete(USER_ID, ADMIN_ID, "ABUSE", "contenu inapproprié répété");

        verify(auditService).log(
                eq("USER"), eq(USER_ID), eq("USER_ADMIN_DELETION"), eq(ADMIN_ID),
                payloadCaptor.capture());

        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload).containsEntry("reasonCode", "ABUSE");
        assertThat(payload).containsEntry("impact_open_dispute", 2);
        assertThat(payload).containsEntry("impact_ratings_given", 5);
        // Aucun motif libre dans le payload.
        assertThat(payload).doesNotContainKey("reason");
    }
}
