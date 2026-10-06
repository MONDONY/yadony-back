package com.yadony.api.payments.integrity;

import com.yadony.api.admin.AdminAlertEscalator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MoneyIntegrityMonitorTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AdminAlertEscalator alertEscalator = mock(AdminAlertEscalator.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private MoneyIntegrityMonitor monitor(boolean enabled) {
        return new MoneyIntegrityMonitor(jdbcTemplate, mock(PlatformTransactionManager.class),
                alertEscalator, registry, enabled);
    }

    @Test
    void uneRegleEnEchecPasseSaJaugeAMoinsUnSansBloquerLesAutres() {
        String failing = MoneyInvariants.ALL.get(2).sql();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.queryForObject(contains(failing), eq(Long.class)))
                .thenThrow(new QueryTimeoutException("statement timeout"));

        Map<String, Long> counts = monitor(true).runAll();

        assertThat(counts).doesNotContainKey("INV-03").hasSize(MoneyInvariants.ALL.size() - 1);
        assertThat(registry.get("yadony.money.invariant.violations").tag("invariant", "INV-03").gauge().value())
                .isEqualTo(-1.0);
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void desactivee_lePassagePlanifieNInterrogeRien() {
        monitor(false).scheduledRun();

        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class));
    }

    @Test
    void uneHausseLeveLAlerteAvecLaGraviteDeLaRegleEtUnExtraitDesLignes() {
        String inv05 = MoneyInvariants.ALL.get(4).sql();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.queryForObject(contains(inv05), eq(Long.class))).thenReturn(2L);
        Map<String, Object> row = new LinkedHashMap<>();
        UUID paymentId = UUID.randomUUID();
        row.put("payment_id", paymentId);
        row.put("amount", new BigDecimal("12.50"));
        row.put("force_release_admin", false);
        row.put("escrow_released_at", Timestamp.valueOf("2026-10-01 10:00:00"));
        row.put("bid_id", null);
        when(jdbcTemplate.queryForList(contains(inv05))).thenReturn(List.of(row));

        monitor(true).runAll();

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Map<String, Object>> context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(alertEscalator).raiseOnce(eq("MONEY_INVARIANT_INV-05"), eq("CRITICAL"), contains("2 ligne(s)"),
                context.capture());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exemples = (List<Map<String, Object>>) context.getValue().get("exemples");
        assertThat(exemples).hasSize(1);
        assertThat(exemples.get(0))
                .containsEntry("payment_id", paymentId.toString())
                .containsEntry("amount", new BigDecimal("12.50"))
                .containsEntry("force_release_admin", false)
                .containsEntry("escrow_released_at", "2026-10-01 10:00:00.0")
                .containsEntry("bid_id", null);
    }

    @Test
    void lExtraitEnEchecNEmpechePasLAlerte() {
        String inv14 = MoneyInvariants.ALL.get(13).sql();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(0L);
        when(jdbcTemplate.queryForObject(contains(inv14), eq(Long.class))).thenReturn(1L);
        when(jdbcTemplate.queryForList(contains(inv14))).thenThrow(new QueryTimeoutException("timeout"));

        monitor(true).runAll();

        verify(alertEscalator).raiseOnce(eq("MONEY_INVARIANT_INV-14"), eq("WARN"), anyString(),
                org.mockito.ArgumentMatchers.argThat(ctx -> !ctx.containsKey("exemples")));
    }

    @Test
    void inspectPlafonneLeNombreDeLignes() {
        String inv01 = MoneyInvariants.ALL.get(0).sql();
        when(jdbcTemplate.queryForObject(contains(inv01), eq(Long.class))).thenReturn(500L);
        when(jdbcTemplate.queryForList(contains(inv01))).thenReturn(List.of());

        MoneyIntegrityMonitor.Inspection inspection = monitor(true).inspect("INV-01", 10_000).orElseThrow();

        assertThat(inspection.total()).isEqualTo(500L);
        assertThat(inspection.title()).isEqualTo(MoneyInvariants.ALL.get(0).title());
        verify(jdbcTemplate).queryForList(contains("LIMIT " + MoneyIntegrityMonitor.MAX_INSPECT_ROWS));
        assertThat(monitor(true).inspect("INV-404", 10)).isEmpty();
    }
}
