package com.yadony.api.payments;

import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PendingCardPaymentAutoHealJobTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentStripeResyncService resync;
    @Mock private DeliveredEscrowReleaser releaser;

    private PendingCardPaymentAutoHealJob job;

    @BeforeEach
    void setUp() {
        job = newJob(true, 20);
    }

    private PendingCardPaymentAutoHealJob newJob(boolean enabled, int batch) {
        return newJob(enabled, batch, NOW);
    }

    private PendingCardPaymentAutoHealJob newJob(boolean enabled, int batch, Instant now) {
        return new PendingCardPaymentAutoHealJob(paymentRepository, resync, releaser, enabled, Duration.ofMinutes(10),
                Duration.ofDays(7), Duration.ofHours(24), batch, Clock.fixed(now, ZoneOffset.UTC));
    }

    private UUID pendingCreated(Duration ago) {
        UUID id = UUID.randomUUID();
        PaymentEntity p = new PaymentEntity();
        ReflectionTestUtils.setField(p, "id", id);
        ReflectionTestUtils.setField(p, "createdAt", LocalDateTime.ofInstant(NOW.minus(ago), ZoneOffset.UTC));
        p.setAmount(BigDecimal.TEN);
        p.setCommissionAmount(BigDecimal.ONE);
        lenient().when(paymentRepository.findById(id)).thenReturn(Optional.of(p));
        return id;
    }

    private void candidates(UUID... ids) {
        lenient().when(paymentRepository.findPendingCardPaymentIds(any(), any(), any(Pageable.class))).thenReturn(List.of(ids));
    }

    private static PaymentStripeResyncService.Result result(UUID id, PaymentStripeResyncService.Action action,
                                                            String stripeStatus, boolean released) {
        var before = new PaymentStripeResyncService.Snapshot("PENDING", null, null, stripeStatus, 0L);
        var after = new PaymentStripeResyncService.Snapshot(released ? "RELEASED" : "ESCROW", null, null, stripeStatus, 0L);
        return new PaymentStripeResyncService.Result(id, "pi", action, before, after, "m", released);
    }

    @Test
    void queriesTheWindow_minAgeToMaxAge() {
        candidates();

        job.run();

        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        verify(paymentRepository).findPendingCardPaymentIds(eq(now.minusMinutes(10)), eq(now.minusDays(7)),
                any(Pageable.class));
        verifyNoInteractions(resync, releaser);
    }

    @Test
    void healedPayment_isNotScheduled_andCounted() {
        UUID id = pendingCreated(Duration.ofHours(2));
        candidates(id);
        when(resync.resyncAutomatically(id))
                .thenReturn(result(id, PaymentStripeResyncService.Action.ESCROW_ACTIVATED, "requires_capture", true));

        assertThat(job.run()).isEqualTo(1);
        assertThat(job.nextCheckOf(id)).isNull();
    }

    @Test
    void inSyncPayment_isReadLessAndLessOften() {
        UUID young = pendingCreated(Duration.ofMinutes(30));
        UUID day = pendingCreated(Duration.ofHours(5));
        UUID old = pendingCreated(Duration.ofHours(30));
        candidates(young, day, old);
        when(resync.resyncAutomatically(any())).thenAnswer(inv ->
                result(inv.getArgument(0), PaymentStripeResyncService.Action.ALREADY_IN_SYNC, "processing", false));

        job.run();

        assertThat(job.nextCheckOf(young)).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(job.nextCheckOf(day)).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(job.nextCheckOf(old)).isEqualTo(NOW.plus(Duration.ofHours(6)));

        // Passage suivant, même instant : aucun n'est dû, aucun appel Stripe.
        clearInvocations(resync);
        job.run();
        verifyNoInteractions(resync);
    }

    @Test
    void abandonedCheckout_isNoLongerRead() {
        UUID abandoned = pendingCreated(Duration.ofHours(25));
        UUID fresh = pendingCreated(Duration.ofHours(2));
        candidates(abandoned, fresh);
        when(resync.resyncAutomatically(any())).thenAnswer(inv -> result(inv.getArgument(0),
                PaymentStripeResyncService.Action.ALREADY_IN_SYNC, "requires_payment_method", false));

        job.run();

        assertThat(job.nextCheckOf(abandoned)).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(job.nextCheckOf(fresh)).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void stripeUnavailable_stopsThePass() {
        UUID first = pendingCreated(Duration.ofHours(1));
        UUID second = pendingCreated(Duration.ofHours(1));
        candidates(first, second);
        when(resync.resyncAutomatically(first)).thenThrow(new YadonyBusinessException(HttpStatus.BAD_GATEWAY,
                "stripe-unavailable", "Stripe Error", "down"));

        job.run();

        verify(resync, never()).resyncAutomatically(second);
        assertThat(job.nextCheckOf(first)).isNull();
    }

    @Test
    void unsupportedGap_andUnexpectedError_areRetriedLater() {
        UUID conflict = pendingCreated(Duration.ofHours(1));
        UUID broken = pendingCreated(Duration.ofHours(1));
        candidates(conflict, broken);
        when(resync.resyncAutomatically(conflict)).thenThrow(new YadonyBusinessException(HttpStatus.CONFLICT,
                "amount-mismatch", "Amount Mismatch", "écart"));
        when(resync.resyncAutomatically(broken)).thenThrow(new IllegalStateException("boom"));

        assertThat(job.run()).isEqualTo(2);

        assertThat(job.nextCheckOf(conflict)).isEqualTo(NOW.plus(Duration.ofHours(6)));
        assertThat(job.nextCheckOf(broken)).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void batchSize_boundsTheStripeCalls() {
        PendingCardPaymentAutoHealJob small = newJob(true, 1);
        UUID a = pendingCreated(Duration.ofHours(1));
        UUID b = pendingCreated(Duration.ofHours(1));
        candidates(a, b);
        when(resync.resyncAutomatically(a))
                .thenReturn(result(a, PaymentStripeResyncService.Action.MARKED_CANCELLED, "canceled", false));

        assertThat(small.run()).isEqualTo(1);
        verify(resync, never()).resyncAutomatically(b);
    }

    @Test
    void disabled_scheduledRunDoesNothing_enabledRuns() {
        newJob(false, 20).scheduledRun();
        verifyNoInteractions(paymentRepository, resync);

        candidates();
        job.scheduledRun();
        verify(paymentRepository).findPendingCardPaymentIds(any(), any(), any(Pageable.class));
    }

    @Test
    void missingPaymentOrCreatedAt_fallsBackSafely() {
        UUID gone = UUID.randomUUID();
        when(paymentRepository.findById(gone)).thenReturn(Optional.empty());
        UUID noDate = UUID.randomUUID();
        PaymentEntity p = new PaymentEntity();
        when(paymentRepository.findById(noDate)).thenReturn(Optional.of(p));
        candidates(gone, noDate);
        when(resync.resyncAutomatically(any())).thenAnswer(inv ->
                result(inv.getArgument(0), PaymentStripeResyncService.Action.ALREADY_IN_SYNC, null, false));

        job.run();

        assertThat(job.nextCheckOf(gone)).isEqualTo(NOW.plus(Duration.ofHours(6)));
        assertThat(job.nextCheckOf(noDate)).isEqualTo(NOW.plus(Duration.ofMinutes(30)));
    }

    // ── Famine : plus récents d'abord, pagination jusqu'à remplir le lot ─────

    @Test
    void pagination_skipsPaymentsOnBackoff_untilTheBatchIsFull() {
        // Lot de 2 → pages de 10. Page 0 : 10 paiements déjà relus (en attente) ; page 1 : 2 dus.
        PendingCardPaymentAutoHealJob small = newJob(true, 2);
        List<UUID> waiting = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) waiting.add(pendingCreated(Duration.ofHours(2)));
        UUID dueA = pendingCreated(Duration.ofHours(3));
        UUID dueB = pendingCreated(Duration.ofHours(3));
        when(paymentRepository.findPendingCardPaymentIds(any(), any(), eq(org.springframework.data.domain.PageRequest.of(0, 10))))
                .thenReturn(waiting);
        when(paymentRepository.findPendingCardPaymentIds(any(), any(), eq(org.springframework.data.domain.PageRequest.of(1, 10))))
                .thenReturn(List.of(dueA, dueB));
        when(resync.resyncAutomatically(any())).thenAnswer(inv ->
                result(inv.getArgument(0), PaymentStripeResyncService.Action.ALREADY_IN_SYNC, "processing", false));
        // Premier passage : les deux plus récents de la page 0 passent et partent en attente.
        small.run();
        // Puis les 8 autres de la page 0, deux par passage.
        for (int i = 0; i < 4; i++) small.run();
        clearInvocations(resync);

        small.run();

        verify(resync).resyncAutomatically(dueA);
        verify(resync).resyncAutomatically(dueB);
        verify(resync, times(2)).resyncAutomatically(any());
    }

    @Test
    void pagination_stopsAtTheLastPartialPage_andAtMaxPages() {
        UUID only = pendingCreated(Duration.ofHours(2));
        candidates(only);
        when(resync.resyncAutomatically(only))
                .thenReturn(result(only, PaymentStripeResyncService.Action.ALREADY_IN_SYNC, "processing", false));

        job.run();
        job.run();

        // Une seule page lue par passage : elle était incomplète.
        verify(paymentRepository, times(2)).findPendingCardPaymentIds(any(), any(), any(Pageable.class));

        // Pages toujours pleines de paiements en attente : au plus MAX_PAGES lectures.
        PendingCardPaymentAutoHealJob small = newJob(true, 1);
        List<UUID> full = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) full.add(pendingCreated(Duration.ofHours(2)));
        clearInvocations(paymentRepository);
        when(paymentRepository.findPendingCardPaymentIds(any(), any(), any(Pageable.class))).thenReturn(full);
        when(resync.resyncAutomatically(any())).thenAnswer(inv ->
                result(inv.getArgument(0), PaymentStripeResyncService.Action.ALREADY_IN_SYNC, "processing", false));
        for (int i = 0; i < 5; i++) small.run();
        clearInvocations(paymentRepository);
        small.run();
        verify(paymentRepository, times(PendingCardPaymentAutoHealJob.MAX_PAGES))
                .findPendingCardPaymentIds(any(), any(), any(Pageable.class));
    }

    // ── Séquestres livrés non versés ─────────────────────────────────────────

    private void escrowCandidates(UUID... ids) {
        when(paymentRepository.findDeliveredUnreleasedEscrowIds(any(), any(), any(Pageable.class))).thenReturn(List.of(ids));
    }

    @Test
    void deliveredEscrow_queriesTheReleaseWindow() {
        candidates();
        escrowCandidates();

        job.run();

        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
        verify(paymentRepository).findDeliveredUnreleasedEscrowIds(eq(now.minusMinutes(15)), eq(now.minusHours(20)),
                any(Pageable.class));
    }

    @Test
    void deliveredEscrow_released_failed_guarded_andNotDelivered() {
        candidates();
        UUID ok = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        UUID held = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        escrowCandidates(ok, failed, held, gone, broken);
        when(releaser.releaseIfDelivered(ok, "auto-heal-release"))
                .thenReturn(DeliveredEscrowReleaser.LateRelease.of(EscrowReleaseOutcome.RELEASED));
        when(releaser.releaseIfDelivered(failed, "auto-heal-release"))
                .thenReturn(new DeliveredEscrowReleaser.LateRelease(EscrowReleaseOutcome.TRANSFER_FAILED, "refus"));
        when(releaser.releaseIfDelivered(held, "auto-heal-release"))
                .thenReturn(DeliveredEscrowReleaser.LateRelease.of(EscrowReleaseOutcome.PAYOUT_HELD));
        when(releaser.releaseIfDelivered(gone, "auto-heal-release"))
                .thenReturn(DeliveredEscrowReleaser.LateRelease.of(EscrowReleaseOutcome.ALREADY_RELEASED));
        when(releaser.releaseIfDelivered(broken, "auto-heal-release")).thenThrow(new IllegalStateException("db"));

        assertThat(job.run()).isEqualTo(5);

        assertThat(job.nextReleaseOf(ok)).isNull();
        assertThat(job.nextReleaseOf(gone)).isNull();
        assertThat(job.nextReleaseOf(failed)).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(job.nextReleaseOf(broken)).isEqualTo(NOW.plus(Duration.ofHours(1)));
        // Garde bloquante (voyageur gelé…) : plus d'essai, donc pas de boucle d'audits ni d'alertes.
        assertThat(job.nextReleaseOf(held)).isEqualTo(NOW.plus(Duration.ofHours(20)));

        // Passage suivant : échecs et gardes attendent ; seuls les paiements sortis de l'agenda sont
        // relus (en vrai, versés ou plus en séquestre, la requête ne les renvoie plus).
        clearInvocations(releaser);
        job.run();
        verify(releaser, never()).releaseIfDelivered(eq(failed), any());
        verify(releaser, never()).releaseIfDelivered(eq(held), any());
        verify(releaser, never()).releaseIfDelivered(eq(broken), any());
    }
}
