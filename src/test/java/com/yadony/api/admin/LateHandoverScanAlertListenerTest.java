package com.yadony.api.admin;

import com.yadony.api.common.AuditLogRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.tracking.events.LateHandoverScanEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LateHandoverScanAlertListenerTest {

    private final AdminAlertEscalator escalator = mock(AdminAlertEscalator.class);
    private final AuditService auditService = mock(AuditService.class);
    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final LateHandoverScanAlertListener listener =
            new LateHandoverScanAlertListener(escalator, auditService, auditLogRepository, tm);

    private final UUID bidId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final LocalDateTime decidedAt = LocalDateTime.of(2026, 10, 30, 10, 0);
    private final LocalDateTime offline = LocalDateTime.of(2026, 10, 29, 9, 15);

    private LateHandoverScanEvent event(String photoKey, boolean proofValidated) {
        return new LateHandoverScanEvent(bidId, travelerId, "CANCELLED", "SENDER_NO_SHOW", decidedAt, offline,
                proofValidated ? "MANUAL" : "QR", photoKey, proofValidated, proofValidated ? "TRACKING_NUMBER" : "NONE");
    }

    private Map<String, Object> expectedContext(String photoKey, boolean proofValidated) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clientDeclared", true);
        m.put("verified", false);
        m.put("proofValidated", proofValidated);
        m.put("proofMethod", proofValidated ? "TRACKING_NUMBER" : "NONE");
        m.put("bidId", bidId.toString());
        m.put("bidStatus", "CANCELLED");
        m.put("rejectionReason", "SENDER_NO_SHOW");
        m.put("decidedAt", "2026-10-30T10:00");
        m.put("offlineTimestamp", "2026-10-29T09:15");
        m.put("scanMethod", proofValidated ? "MANUAL" : "QR");
        if (photoKey != null) m.put("photoKey", photoKey);
        return m;
    }

    @Test
    void sansPreuveVerifiee_alerteAvertissement_puisAuditDansSaPropreTransaction() {
        String photo = "tracking/" + bidId + "/1.jpg";
        when(auditLogRepository.findLastActionAt("BID", bidId, "LATE_HANDOVER_SCAN_AFTER_DECISION"))
                .thenReturn(Optional.empty());

        listener.onLateHandoverScan(event(photo, false));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        var order = inOrder(auditLogRepository, escalator, tm, auditService);
        // Lecture de déduplication dans la transaction du scan : aucune transaction ouverte pour elle.
        order.verify(auditLogRepository).findLastActionAt("BID", bidId, "LATE_HANDOVER_SCAN_AFTER_DECISION");
        order.verify(escalator).raiseOnce(eq("LATE_HANDOVER_CLAIM_" + bidId), eq("WARN"), detail.capture(),
                eq(expectedContext(photo, false)));
        order.verify(tm).getTransaction(argThat(d ->
                d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        order.verify(auditService).log("BID", bidId, "LATE_HANDOVER_SCAN_AFTER_DECISION", travelerId,
                expectedContext(photo, false));
        order.verify(tm).commit(any());
        assertThat(detail.getValue()).startsWith("DÉCLARÉ PAR LE CLIENT, NON VÉRIFIÉ")
                .contains("le 2026-10-29T09:15 UTC").contains("le 2026-10-30T10:00 UTC")
                .contains("Preuve vérifiée : aucune");
    }

    @Test
    void numeroDeSuiviVerifie_alerteCritique_maisToujoursDeclaree() {
        listener.onLateHandoverScan(event(null, true));

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(escalator).raiseOnce(eq("LATE_HANDOVER_SCAN_" + bidId), eq("CRITICAL"), detail.capture(),
                eq(expectedContext(null, true)));
        assertThat(detail.getValue()).startsWith("DÉCLARÉ PAR LE CLIENT, NON VÉRIFIÉ")
                .contains("Preuve vérifiée : numéro de suivi");
    }

    @Test
    void nRejeux_uneSeuleTransactionIndependanteAuTotal() {
        // Premier rejeu : rien n'est encore audité ; les suivants voient l'audit commité.
        when(auditLogRepository.findLastActionAt("BID", bidId, "LATE_HANDOVER_SCAN_AFTER_DECISION"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(LocalDateTime.of(2026, 10, 30, 10, 5)));

        for (int i = 0; i < 5; i++) {
            listener.onLateHandoverScan(event(null, false));
        }

        verify(tm, times(1)).getTransaction(any());
        verify(escalator, times(1)).raiseOnce(anyString(), anyString(), anyString(), anyMap());
        verify(auditService, times(1)).log(any(), any(), any(), any(), any());
        verify(auditLogRepository, times(5)).findLastActionAt(any(), any(), any());
    }

    @Test
    void premiereDeclarationFigeLeSignalement_unRejeuUlterieurAvecPreuveNEstPasRetenu() {
        when(auditLogRepository.findLastActionAt("BID", bidId, "LATE_HANDOVER_SCAN_AFTER_DECISION"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(LocalDateTime.of(2026, 10, 30, 10, 5)));

        listener.onLateHandoverScan(event(null, false));
        listener.onLateHandoverScan(event(null, true));

        verify(escalator).raiseOnce(eq("LATE_HANDOVER_CLAIM_" + bidId), eq("WARN"), anyString(), anyMap());
        verify(escalator, never()).raiseOnce(eq("LATE_HANDOVER_SCAN_" + bidId), anyString(), anyString(), anyMap());
    }

    @Test
    void dejaSignalePourCeColis_niAlerteNiAudit_memeApresTraitementDeLAlerte() {
        when(auditLogRepository.findLastActionAt("BID", bidId, "LATE_HANDOVER_SCAN_AFTER_DECISION"))
                .thenReturn(Optional.of(LocalDateTime.of(2026, 10, 30, 10, 5)));

        listener.onLateHandoverScan(event(null, false));

        verifyNoInteractions(escalator, auditService);
    }

    @Test
    void sansPhotoNiMethode_leContexteLesOmet() {
        listener.onLateHandoverScan(new LateHandoverScanEvent(bidId, travelerId, "NO_SHOW", null, decidedAt, offline,
                null, null, false, "NONE"));

        ArgumentCaptor<Map<String, Object>> ctx = mapCaptor();
        verify(escalator).raiseOnce(anyString(), eq("WARN"), anyString(), ctx.capture());
        assertThat(ctx.getValue()).doesNotContainKeys("scanMethod", "photoKey")
                .containsEntry("rejectionReason", "null").containsEntry("bidStatus", "NO_SHOW");
    }

    @Test
    void uneAlerteOuUnAuditEnPanne_neFaitJamaisEchouerLeScan() {
        when(escalator.raiseOnce(anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("base indisponible"));
        doThrow(new IllegalStateException("audit")).when(auditService).log(any(), any(), any(), any(), any());

        assertThatCode(() -> listener.onLateHandoverScan(event(null, false))).doesNotThrowAnyException();
    }

    @Test
    void verificationDuDoublonImpossible_onNeSignalePas() {
        when(auditLogRepository.findLastActionAt(any(), any(), any())).thenThrow(new IllegalStateException("base"));

        assertThatCode(() -> listener.onLateHandoverScan(event(null, false))).doesNotThrowAnyException();
        verify(escalator, never()).raiseOnce(anyString(), anyString(), anyString(), anyMap());
        verifyNoInteractions(auditService, tm);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
