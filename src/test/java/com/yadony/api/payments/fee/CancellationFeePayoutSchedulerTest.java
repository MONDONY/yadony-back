package com.yadony.api.payments.fee;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Reprise horaire des versements en attente et des règlements dont l'écouteur s'est perdu. */
@ExtendWith(MockitoExtension.class)
class CancellationFeePayoutSchedulerTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    @Mock CancellationFeePayoutPort port;
    @Mock CancellationFeePayoutService payoutService;

    private CancellationFeePayoutScheduler scheduler() {
        return new CancellationFeePayoutScheduler(port, payoutService, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void run_retriesEveryPendingRow_andRetainedRowsOlderThanTenMinutes() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(port.findPayoutRetries(Instant.parse("2026-11-21T09:50:00Z"))).thenReturn(List.of(first, second));
        when(payoutService.payOut(first)).thenReturn(true);
        when(payoutService.payOut(second)).thenReturn(false);

        assertThat(scheduler().run()).isEqualTo(1);

        verify(payoutService).payOut(first);
        verify(payoutService).payOut(second);
    }

    @Test
    void run_oneFailingRow_doesNotBlockTheBatch() {
        UUID failing = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        when(port.findPayoutRetries(Instant.parse("2026-11-21T09:50:00Z"))).thenReturn(List.of(failing, next));
        when(payoutService.payOut(failing)).thenThrow(new IllegalStateException("boom"));
        when(payoutService.payOut(next)).thenReturn(true);

        assertThat(scheduler().run()).isEqualTo(1);

        verify(payoutService).payOut(next);
    }

    @Test
    void run_lookupFailure_paysNothing() {
        when(port.findPayoutRetries(Instant.parse("2026-11-21T09:50:00Z"))).thenThrow(new IllegalStateException("db"));

        assertThat(scheduler().run()).isZero();

        verifyNoInteractions(payoutService);
    }

    @Test
    void retryPayouts_runsAPass() {
        when(port.findPayoutRetries(Instant.parse("2026-11-21T09:50:00Z"))).thenReturn(List.of());

        scheduler().retryPayouts();

        verify(port).findPayoutRetries(Instant.parse("2026-11-21T09:50:00Z"));
    }

    /** Une ligne, ses transactions : une transaction englobante tiendrait une connexion pendant les appels Stripe. */
    @Test
    void hourlyUtc_withoutSurroundingTransaction() throws Exception {
        Method method = CancellationFeePayoutScheduler.class.getMethod("retryPayouts");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 0 * * * *");
        assertThat(scheduled.zone()).isEqualTo("UTC");
        assertThat(method.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(CancellationFeePayoutScheduler.class.isAnnotationPresent(Transactional.class)).isFalse();
    }
}
