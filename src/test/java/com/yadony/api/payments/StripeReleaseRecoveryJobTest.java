package com.yadony.api.payments;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reprise des versements réservés : chaque réservation traitée seule, un échec n'arrête pas les autres. */
class StripeReleaseRecoveryJobTest {

    private final StripeReleaseIntentRepository intents = mock(StripeReleaseIntentRepository.class);
    private final StripeReleaseExecutor executor = mock(StripeReleaseExecutor.class);

    @Test
    void run_repriseUneParUne_etCompteCellesTraitees() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(intents.findDueIds(any(), any(Pageable.class))).thenReturn(List.of(a, b, c));
        when(executor.resume(a)).thenReturn(new StripeReleaseExecutor.Execution(
                StripeReleaseExecutor.Result.REALIGNED, "tr_1", null));
        when(executor.resume(b)).thenThrow(new IllegalStateException("db"));
        when(executor.resume(c)).thenReturn(new StripeReleaseExecutor.Execution(
                StripeReleaseExecutor.Result.NOT_OWNED, null, null));

        int resumed = new StripeReleaseRecoveryJob(intents, executor, true, 20).run();

        assertThat(resumed).isEqualTo(1);
        verify(executor).resume(a);
        verify(executor).resume(b);
        verify(executor).resume(c);
    }

    @Test
    void run_repriseIncertaine_estComptee() {
        UUID a = UUID.randomUUID();
        when(intents.findDueIds(any(), any(Pageable.class))).thenReturn(List.of(a));
        when(executor.resume(a)).thenReturn(new StripeReleaseExecutor.Execution(
                StripeReleaseExecutor.Result.PENDING_RETRY, null, "ApiConnectionException: timeout"));
        assertThat(new StripeReleaseRecoveryJob(intents, executor, true, 0).run()).isEqualTo(1);
    }

    @Test
    void scheduledRun_desactive_neLitRien() {
        new StripeReleaseRecoveryJob(intents, executor, false, 20).scheduledRun();
        verify(intents, never()).findDueIds(any(), any());
    }

    @Test
    void scheduledRun_actif_lanceUnPassage() {
        when(intents.findDueIds(any(), any(Pageable.class))).thenReturn(List.of());
        new StripeReleaseRecoveryJob(intents, executor, true, 20).scheduledRun();
        verify(intents).findDueIds(any(), any(Pageable.class));
    }
}
