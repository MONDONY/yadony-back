package com.yadony.api.kyc.erasure;

import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.kyc.provider.didit.DiditClient;
import com.yadony.api.kyc.provider.didit.DiditClient.DeletionOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiditSessionErasureServiceTest {

    @Mock DiditSessionDeletionRepository repository;
    @Mock DiditClient diditClient;
    @Mock AuditService auditService;
    @Mock AdminAlertService adminAlertService;
    @Mock PlatformTransactionManager transactionManager;

    DiditSessionErasureService service;

    static final UUID USER_ID = UUID.randomUUID();
    static final String SESSION = "11111111-2222-3333-4444-555555555555";

    @BeforeEach
    void setUp() {
        service = new DiditSessionErasureService(repository, diditClient, auditService, adminAlertService,
                transactionManager, 3, 20);
    }

    private DiditSessionDeletionEntity row(UUID id, int attempts) {
        DiditSessionDeletionEntity row = new DiditSessionDeletionEntity(USER_ID, SESSION,
                LocalDateTime.now(ZoneOffset.UTC));
        row.setAttempts(attempts);
        setId(row, id);
        return row;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private DiditSessionDeletionEntity claimable(int attempts) {
        UUID id = UUID.randomUUID();
        DiditSessionDeletionEntity row = row(id, attempts);
        when(repository.claim(eq(id), any(), any())).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void enqueue_dedoublonneIgnoreLesVidesEtLesDejaInscrites() {
        when(repository.existsBySessionId("deja")).thenReturn(true);
        when(repository.existsBySessionId(SESSION)).thenReturn(false);

        int created = service.enqueue(USER_ID, Arrays.asList(SESSION, SESSION, " ", null, "deja"));

        assertThat(created).isEqualTo(1);
        ArgumentCaptor<DiditSessionDeletionEntity> saved = ArgumentCaptor.forClass(DiditSessionDeletionEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSessionId()).isEqualTo(SESSION);
        assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo(DiditSessionDeletionEntity.Status.PENDING);
        assertThat(saved.getValue().getNextAttemptAt()).isNotNull();
    }

    @Test
    void attempt_ligneDejaPriseParUnAutreEssai_neContactePasDidit() {
        UUID id = UUID.randomUUID();
        when(repository.claim(eq(id), any(), any())).thenReturn(0);

        assertThat(service.attempt(id)).isFalse();

        verify(diditClient, never()).deleteSession(anyString(), anyString());
    }

    @Test
    void attempt_effacee_passeDoneEtAudite() {
        DiditSessionDeletionEntity row = claimable(0);
        when(diditClient.deleteSession(SESSION, "user-deletion-" + USER_ID))
                .thenReturn(new DeletionOutcome(DeletionOutcome.Status.DELETED, null));

        assertThat(service.attempt(row.getId())).isTrue();

        assertThat(row.getStatus()).isEqualTo(DiditSessionDeletionEntity.Status.DONE);
        assertThat(row.getCompletedAt()).isNotNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(USER_ID), eq("KYC_PROVIDER_SESSION_ERASED"), isNull(), payload.capture());
        assertThat(payload.getValue()).containsEntry("provider", "DIDIT").containsEntry("outcome", "DELETED")
                .containsEntry("sessionId", SESSION);
        verify(adminAlertService, never()).raise(anyString(), anyString(), anyMap());
    }

    @Test
    void attempt_dejaEffaceeChezDidit_passeDone() {
        DiditSessionDeletionEntity row = claimable(0);
        when(diditClient.deleteSession(anyString(), anyString()))
                .thenReturn(new DeletionOutcome(DeletionOutcome.Status.ALREADY_DELETED, null));

        service.attempt(row.getId());

        assertThat(row.getStatus()).isEqualTo(DiditSessionDeletionEntity.Status.DONE);
        verify(auditService).log(eq("USER"), eq(USER_ID), eq("KYC_PROVIDER_SESSION_ERASED"), isNull(), anyMap());
    }

    @Test
    void attempt_echecTransitoire_reporteSansAlerte() {
        DiditSessionDeletionEntity row = claimable(0);
        when(diditClient.deleteSession(anyString(), anyString()))
                .thenReturn(new DeletionOutcome(DeletionOutcome.Status.FAILED, 503));
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        service.attempt(row.getId());

        assertThat(row.getStatus()).isEqualTo(DiditSessionDeletionEntity.Status.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastHttpStatus()).isEqualTo(503);
        assertThat(row.getNextAttemptAt()).isAfterOrEqualTo(before.plusMinutes(15));
        verify(repository).save(row);
        verify(adminAlertService, never()).raise(anyString(), anyString(), anyMap());
        verify(auditService, never()).log(anyString(), any(), anyString(), any(), anyMap());
    }

    @Test
    void attempt_premier403_alerteSurLaPermission() {
        DiditSessionDeletionEntity row = claimable(0);
        when(diditClient.deleteSession(anyString(), anyString()))
                .thenReturn(new DeletionOutcome(DeletionOutcome.Status.FAILED, 403));

        service.attempt(row.getId());

        assertThat(row.getStatus()).isEqualTo(DiditSessionDeletionEntity.Status.PENDING);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(adminAlertService).raise(eq(DiditSessionErasureService.ALERT_CODE), detail.capture(), anyMap());
        assertThat(detail.getValue()).contains("delete:sessions");
    }

    @Test
    void attempt_403Repete_pasDeNouvelleAlerteAvantLAbandon() {
        DiditSessionDeletionEntity row = claimable(1);
        when(diditClient.deleteSession(anyString(), anyString()))
                .thenReturn(new DeletionOutcome(DeletionOutcome.Status.FAILED, 403));

        service.attempt(row.getId());

        assertThat(row.getAttempts()).isEqualTo(2);
        verify(adminAlertService, never()).raise(anyString(), anyString(), anyMap());
    }

    @Test
    void attempt_dernierEssai_abandonneAlerteEtAudite() {
        DiditSessionDeletionEntity row = claimable(2);
        when(diditClient.deleteSession(anyString(), anyString()))
                .thenReturn(new DeletionOutcome(DeletionOutcome.Status.FAILED, null));

        service.attempt(row.getId());

        assertThat(row.getStatus()).isEqualTo(DiditSessionDeletionEntity.Status.FAILED);
        assertThat(row.getAttempts()).isEqualTo(3);
        verify(auditService).log(eq("USER"), eq(USER_ID), eq("KYC_PROVIDER_SESSION_ERASURE_FAILED"), isNull(), anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> context = ArgumentCaptor.forClass(Map.class);
        verify(adminAlertService).raise(eq(DiditSessionErasureService.ALERT_CODE), anyString(), context.capture());
        assertThat(context.getValue()).containsEntry("httpStatus", "aucune réponse").containsEntry("userId", USER_ID);
    }

    @Test
    void attempt_ligneCloseEntreTemps_rienAEcrire() {
        UUID id = UUID.randomUUID();
        DiditSessionDeletionEntity claimed = row(id, 0);
        DiditSessionDeletionEntity closed = row(id, 0);
        closed.setStatus(DiditSessionDeletionEntity.Status.DONE);
        when(repository.claim(eq(id), any(), any())).thenReturn(1);
        when(repository.findById(id)).thenReturn(Optional.of(claimed), Optional.of(closed));
        when(diditClient.deleteSession(anyString(), anyString()))
                .thenReturn(new DeletionOutcome(DeletionOutcome.Status.FAILED, 503));

        service.attempt(id);

        verify(repository, never()).save(any());
    }

    @Test
    void attemptDue_traiteLesLignesDuesEtSurvitAUneErreur() {
        UUID broken = UUID.randomUUID();
        DiditSessionDeletionEntity ok = claimable(0);
        when(repository.findDueIds(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(broken, ok.getId()));
        when(repository.claim(eq(broken), any(), any())).thenThrow(new IllegalStateException("db"));
        when(diditClient.deleteSession(anyString(), anyString()))
                .thenReturn(new DeletionOutcome(DeletionOutcome.Status.DELETED, null));

        assertThat(service.attemptDue()).isEqualTo(1);
        assertThat(ok.getStatus()).isEqualTo(DiditSessionDeletionEntity.Status.DONE);
    }

    @Test
    void attemptPendingFor_essaieChaqueLigneDeLUtilisateur() {
        DiditSessionDeletionEntity row = claimable(0);
        when(repository.findPendingIdsByUserId(USER_ID)).thenReturn(List.of(row.getId()));
        when(diditClient.deleteSession(anyString(), anyString()))
                .thenReturn(new DeletionOutcome(DeletionOutcome.Status.DELETED, null));

        service.attemptPendingFor(USER_ID);

        verify(diditClient, times(1)).deleteSession(SESSION, "user-deletion-" + USER_ID);
    }

    @Test
    void retryDelay_doubleEtPlafonneAUnJour() {
        assertThat(DiditSessionErasureService.retryDelay(1)).isEqualTo(Duration.ofMinutes(15));
        assertThat(DiditSessionErasureService.retryDelay(2)).isEqualTo(Duration.ofMinutes(30));
        assertThat(DiditSessionErasureService.retryDelay(4)).isEqualTo(Duration.ofHours(2));
        assertThat(DiditSessionErasureService.retryDelay(30)).isEqualTo(Duration.ofDays(1));
        assertThat(DiditSessionErasureService.retryDelay(0)).isEqualTo(Duration.ofMinutes(15));
    }
}
