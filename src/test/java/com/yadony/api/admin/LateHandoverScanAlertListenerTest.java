package com.yadony.api.admin;

import com.yadony.api.common.AuditService;
import com.yadony.api.tracking.events.LateHandoverScanEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LateHandoverScanAlertListenerTest {

    private final AdminAlertEscalator escalator = mock(AdminAlertEscalator.class);
    private final AuditService auditService = mock(AuditService.class);
    private final PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
    private final LateHandoverScanAlertListener listener = new LateHandoverScanAlertListener(escalator, auditService, tm);

    private final UUID bidId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final LocalDateTime decidedAt = LocalDateTime.of(2026, 10, 30, 10, 0);
    private final LocalDateTime offline = LocalDateTime.of(2026, 10, 29, 9, 15);

    private LateHandoverScanEvent event(String photoKey) {
        return new LateHandoverScanEvent(bidId, travelerId, "CANCELLED", "SENDER_NO_SHOW", decidedAt, offline,
                "QR", photoKey);
    }

    private Map<String, Object> expectedContext(String photoKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bidId", bidId.toString());
        m.put("bidStatus", "CANCELLED");
        m.put("rejectionReason", "SENDER_NO_SHOW");
        m.put("decidedAt", "2026-10-30T10:00");
        m.put("offlineTimestamp", "2026-10-29T09:15");
        m.put("scanMethod", "QR");
        if (photoKey != null) m.put("photoKey", photoKey);
        return m;
    }

    @Test
    void alerteCritiqueParColis_etAuditDansUneTransactionIndependante() {
        String photo = "tracking/" + bidId + "/1.jpg";

        listener.onLateHandoverScan(event(photo));

        verify(escalator).raiseOnce(eq("LATE_HANDOVER_SCAN_" + bidId), eq("CRITICAL"), anyString(),
                eq(expectedContext(photo)));
        var order = inOrder(tm, auditService);
        order.verify(tm).getTransaction(argThat(d ->
                d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        order.verify(auditService).log("BID", bidId, "LATE_HANDOVER_SCAN_AFTER_DECISION", travelerId,
                expectedContext(photo));
        order.verify(tm).commit(any());
    }

    @Test
    void sansPhotoNiMethode_leContexteLesOmet() {
        listener.onLateHandoverScan(new LateHandoverScanEvent(bidId, travelerId, "NO_SHOW", null, decidedAt, offline,
                null, null));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bidId", bidId.toString());
        m.put("bidStatus", "NO_SHOW");
        m.put("rejectionReason", "null");
        m.put("decidedAt", "2026-10-30T10:00");
        m.put("offlineTimestamp", "2026-10-29T09:15");
        verify(escalator).raiseOnce(eq("LATE_HANDOVER_SCAN_" + bidId), eq("CRITICAL"), anyString(), eq(m));
    }

    @Test
    void uneAlerteOuUnAuditEnPanne_neFaitJamaisEchouerLeScan() {
        when(escalator.raiseOnce(anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IllegalStateException("base indisponible"));
        doThrow(new IllegalStateException("audit")).when(auditService).log(any(), any(), any(), any(), any());

        assertThatCode(() -> listener.onLateHandoverScan(event(null))).doesNotThrowAnyException();
    }
}
