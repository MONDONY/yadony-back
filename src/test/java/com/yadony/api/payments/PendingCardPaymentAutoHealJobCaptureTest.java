package com.yadony.api.payments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Passe « séquestres non capturés » du job d'auto-réparation : essais, délais, lot partagé. */
@ExtendWith(MockitoExtension.class)
class PendingCardPaymentAutoHealJobCaptureTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentStripeResyncService resync;
    @Mock DeliveredEscrowReleaser releaser;
    @Mock EscrowCaptureService escrowCapture;

    private PendingCardPaymentAutoHealJob job(int batch, Instant now) {
        return new PendingCardPaymentAutoHealJob(paymentRepository, resync, releaser, escrowCapture, true,
                Duration.ofMinutes(10), Duration.ofDays(7), Duration.ofHours(24), batch, Clock.fixed(now, ZoneOffset.UTC));
    }

    private void candidates(List<UUID> ids) {
        when(paymentRepository.findUncapturedDueEscrowIds(any(), any(), any(), any())).thenReturn(ids);
    }

    @Test
    void capture_reussie_sortDeLAgenda() {
        UUID id = UUID.randomUUID();
        candidates(List.of(id));
        when(escrowCapture.ensureCaptured(id, "auto-heal-capture")).thenReturn(new EscrowCaptureService.Outcome("ch", true));
        PendingCardPaymentAutoHealJob job = job(20, NOW);

        assertThat(job.captureDueEscrows()).isEqualTo(1);
        assertThat(job.nextCaptureOf(id)).isNull();
        // La requête ne reprend que les séquestres créés depuis plus de 2 h.
        verify(paymentRepository).findUncapturedDueEscrowIds(eq(java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusHours(2)),
                eq(EscrowCaptureService.ENGAGED_BID_STATUSES), eq(PendingCardPaymentAutoHealJob.DEAD_THREADS), any());
    }

    @Test
    void echecDeCapture_nouvelEssaiDans1hPuis6h_sansBoucle() {
        UUID id = UUID.randomUUID();
        candidates(List.of(id));
        when(escrowCapture.ensureCaptured(eq(id), any()))
                .thenThrow(new EscrowCaptureService.EscrowCaptureException("refus", "requires_capture", null));

        PendingCardPaymentAutoHealJob first = job(20, NOW);
        first.captureDueEscrows();
        assertThat(first.nextCaptureOf(id)).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(first.captureDueEscrows()).isZero();   // même passage : pas de boucle
        verify(escrowCapture, times(1)).ensureCaptured(eq(id), any());
    }

    @Test
    void echecRepete_delaiDe6h_autorisationPerdue_plusRetentee() {
        UUID retried = UUID.randomUUID();
        UUID lost = UUID.randomUUID();
        candidates(List.of(retried, lost));
        when(escrowCapture.ensureCaptured(eq(retried), any()))
                .thenThrow(new EscrowCaptureService.EscrowCaptureException("refus", null, null));
        when(escrowCapture.ensureCaptured(eq(lost), any()))
                .thenThrow(new EscrowCaptureService.EscrowCaptureException("expirée", "canceled", null));
        Instant[] now = {NOW};
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0]; }
        };
        PendingCardPaymentAutoHealJob job = new PendingCardPaymentAutoHealJob(paymentRepository, resync, releaser,
                escrowCapture, true, Duration.ofMinutes(10), Duration.ofDays(7), Duration.ofHours(24), 20, clock);

        job.captureDueEscrows();
        assertThat(job.nextCaptureOf(lost)).isEqualTo(NOW.plus(Duration.ofDays(7)));
        now[0] = NOW.plus(Duration.ofHours(1));
        job.captureDueEscrows();
        assertThat(job.nextCaptureOf(retried)).isEqualTo(now[0].plus(Duration.ofHours(6)));
        verify(escrowCapture, times(2)).ensureCaptured(eq(retried), any());
        verify(escrowCapture, times(1)).ensureCaptured(eq(lost), any());
    }

    @Test
    void erreurInattendue_nouvelEssaiDans1h() {
        UUID id = UUID.randomUUID();
        candidates(List.of(id));
        when(escrowCapture.ensureCaptured(eq(id), any())).thenThrow(new IllegalStateException("boom"));
        PendingCardPaymentAutoHealJob job = job(20, NOW);

        assertThat(job.captureDueEscrows()).isEqualTo(1);
        assertThat(job.nextCaptureOf(id)).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void lotPartageEntreLesPasses() {
        List<UUID> pending = IntStream.range(0, 2).mapToObj(i -> UUID.randomUUID()).toList();
        List<UUID> uncaptured = IntStream.range(0, 5).mapToObj(i -> UUID.randomUUID()).toList();
        when(paymentRepository.findPendingCardPaymentIds(any(), any(), any())).thenReturn(pending);
        when(resync.resyncAutomatically(any())).thenReturn(mock(PaymentStripeResyncService.Result.class));
        candidates(uncaptured);
        when(escrowCapture.ensureCaptured(any(), any())).thenReturn(new EscrowCaptureService.Outcome(null, false));

        assertThat(job(3, NOW).run()).isEqualTo(3);

        verify(resync, times(2)).resyncAutomatically(any());
        verify(escrowCapture, times(1)).ensureCaptured(any(), any());
        verifyNoInteractions(releaser);
        verify(paymentRepository, never()).findDeliveredUnreleasedEscrowIds(any(), any(), any());
    }

    @Test
    void sansServiceDeCapture_passeIgnoree() {
        PendingCardPaymentAutoHealJob job = new PendingCardPaymentAutoHealJob(paymentRepository, resync, releaser,
                null, true, Duration.ofMinutes(10), Duration.ofDays(7), Duration.ofHours(24), 20,
                Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(job.captureDueEscrows()).isZero();
        verify(paymentRepository, never()).findUncapturedDueEscrowIds(any(), any(), any(), any());
    }
}
