package com.yadony.api.payments.integrity;

import com.yadony.api.admin.AdminAlertEscalator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;

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
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    void desactivee_lePassagePlanifieNInterrogeRien() {
        monitor(false).scheduledRun();

        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class));
    }
}
