package com.yadony.api.payments;

import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import com.yadony.api.tracking.events.ParcelDepositedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Rattrapage planifié du versement au dépôt : même chemin que le dépôt, borné, espacé, alerte au-delà du seuil. */
@ExtendWith(MockitoExtension.class)
class DepositReleaseRetryJobTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    @Mock PaymentRepository paymentRepository;
    @Mock BidRepository bidRepository;
    @Mock AnnouncementRepository announcementRepository;
    @Mock DeliveryEventListener deliveryRelease;
    @Mock AdminAlertEscalator alertEscalator;

    private final UUID announcementId = UUID.randomUUID();
    private final UUID travelerId = UUID.randomUUID();
    private final MutableClock clock = new MutableClock(NOW);

    @BeforeEach
    void setUp() {
        AnnouncementEntity trip = new AnnouncementEntity();
        trip.setTravelerId(travelerId);
        lenient().when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(trip));
    }

    private DepositReleaseRetryJob job(boolean enabled, int batch) {
        return new DepositReleaseRetryJob(paymentRepository, bidRepository, announcementRepository, deliveryRelease,
                alertEscalator, enabled, Duration.ofMinutes(10), Duration.ofDays(3), Duration.ofHours(6), batch, clock);
    }

    @Test
    void lostDeposit_releasedThroughDepositPath_alertResolved() {
        Candidate c = candidate(NOW_UTC.minusMinutes(30));
        when(paymentRepository.findDepositReleaseRetryCandidates(eq(NOW_UTC.minusMinutes(10)),
                eq(NOW_UTC.minusDays(3)), any())).thenReturn(List.of(c));
        when(deliveryRelease.releaseAtDeposit(any())).thenReturn(EscrowReleaseOutcome.RELEASED);

        assertThat(job(true, 20).run()).isEqualTo(1);

        ArgumentCaptor<ParcelDepositedEvent> event = ArgumentCaptor.forClass(ParcelDepositedEvent.class);
        verify(deliveryRelease).releaseAtDeposit(event.capture());
        assertThat(event.getValue().bidId()).isEqualTo(c.bidId);
        assertThat(event.getValue().travelerId()).isEqualTo(travelerId);
        verify(alertEscalator).resolveOpen(DepositReleaseRetryJob.STALLED_ALERT_PREFIX + c.bidId);
        verify(alertEscalator, never()).raiseOnce(anyString(), anyString(), anyMap());
    }

    @Test
    void stillBlocked_retriedLaterWithBackoff_alertAfterThreshold() {
        Candidate fresh = candidate(NOW_UTC.minusMinutes(30));
        Candidate old = candidate(NOW_UTC.minusHours(7));
        when(paymentRepository.findDepositReleaseRetryCandidates(any(), any(), any())).thenReturn(List.of(fresh, old));
        when(deliveryRelease.releaseAtDeposit(any())).thenReturn(EscrowReleaseOutcome.TRANSFER_FAILED);
        DepositReleaseRetryJob job = job(true, 20);

        assertThat(job.run()).isZero();
        verify(deliveryRelease, times(2)).releaseAtDeposit(any());
        verify(alertEscalator).raiseOnce(eq(DepositReleaseRetryJob.STALLED_ALERT_PREFIX + old.bidId), anyString(),
                anyMap());
        verify(alertEscalator, never()).raiseOnce(eq(DepositReleaseRetryJob.STALLED_ALERT_PREFIX + fresh.bidId),
                anyString(), anyMap());

        // Juste après : rien n'est retenté (délai de 10 min).
        clock.now = NOW.plusSeconds(60);
        job.run();
        verify(deliveryRelease, times(2)).releaseAtDeposit(any());

        // 10 min plus tard : retenté, puis délai doublé (20 min).
        clock.now = NOW.plus(Duration.ofMinutes(10));
        job.run();
        verify(deliveryRelease, times(4)).releaseAtDeposit(any());
        clock.now = NOW.plus(Duration.ofMinutes(25));
        job.run();
        verify(deliveryRelease, times(4)).releaseAtDeposit(any());
        clock.now = NOW.plus(Duration.ofMinutes(31));
        job.run();
        verify(deliveryRelease, times(6)).releaseAtDeposit(any());
    }

    @Test
    void batchBound_disabled_andUnknownTrip() {
        List<Candidate> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            many.add(candidate(NOW_UTC.minusMinutes(30)));
        }
        when(paymentRepository.findDepositReleaseRetryCandidates(any(), any(), any())).thenReturn(List.copyOf(many));
        when(deliveryRelease.releaseAtDeposit(any())).thenReturn(EscrowReleaseOutcome.NOT_IN_ESCROW);

        job(true, 2).run();
        verify(deliveryRelease, times(2)).releaseAtDeposit(any());

        DepositReleaseRetryJob disabled = job(false, 20);
        disabled.scheduledRun();
        verify(paymentRepository, times(1)).findDepositReleaseRetryCandidates(any(), any(), any());

        BidEntity orphan = new BidEntity();
        ReflectionTestUtils.setField(orphan, "id", UUID.randomUUID());
        orphan.setAnnouncementId(UUID.randomUUID());
        when(bidRepository.findById(orphan.getId())).thenReturn(Optional.of(orphan));
        Candidate c = new Candidate(orphan.getId(), UUID.randomUUID(), NOW_UTC.minusMinutes(30));
        when(paymentRepository.findDepositReleaseRetryCandidates(any(), any(), any())).thenReturn(List.of(c));
        assertThat(job(true, 20).run()).isZero();
        verify(deliveryRelease, never()).releaseAtDeposit(
                org.mockito.ArgumentMatchers.argThat(e -> e.bidId().equals(orphan.getId())));
    }

    @Test
    void releaseThrows_neverPropagates() {
        Candidate c = candidate(NOW_UTC.minusMinutes(30));
        when(paymentRepository.findDepositReleaseRetryCandidates(any(), any(), any())).thenReturn(List.of(c));
        when(deliveryRelease.releaseAtDeposit(any())).thenThrow(new IllegalStateException("base indisponible"));

        assertThat(job(true, 20).run()).isZero();
        verifyNoInteractions(alertEscalator);
    }

    private Candidate candidate(LocalDateTime depositedAt) {
        BidEntity bid = new BidEntity();
        UUID bidId = UUID.randomUUID();
        ReflectionTestUtils.setField(bid, "id", bidId);
        bid.setAnnouncementId(announcementId);
        bid.setSenderId(UUID.randomUUID());
        lenient().when(bidRepository.findById(bidId)).thenReturn(Optional.of(bid));
        return new Candidate(bidId, UUID.randomUUID(), depositedAt);
    }

    private record Candidate(UUID bidId, UUID paymentId, LocalDateTime depositedAt)
            implements PaymentRepository.DepositRetryCandidate {
        @Override public UUID getBidId() { return bidId; }
        @Override public UUID getPaymentId() { return paymentId; }
        @Override public LocalDateTime getDepositedAt() { return depositedAt; }
    }

    private static final class MutableClock extends Clock {
        Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
