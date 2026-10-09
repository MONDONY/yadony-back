package com.yadony.api.matching;

import com.yadony.api.common.AuditService;
import com.yadony.api.matching.events.BidRejectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BidTimeoutSchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private BidTimeoutScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BidTimeoutScheduler(bidRepository, auditService, eventPublisher);
    }

    private BidEntity timedOutBid() {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        b.setStatus(BidStatus.PENDING);
        b.setSenderId(UUID.randomUUID());
        return b;
    }

    @Test
    void cancels_timed_out_bids_and_publishes_event() {
        BidEntity bid = timedOutBid();
        when(bidRepository.findPendingTimedOut(any(), any(), any())).thenReturn(List.of(bid));

        scheduler.autoCancelUnansweredBids();

        assertThat(bid.getStatus()).isEqualTo(BidStatus.CANCELLED);
        assertThat(bid.getRejectionReason()).isEqualTo("TRAVELER_NO_RESPONSE");

        verify(bidRepository).save(bid);
        verify(auditService).log(eq("BID"), eq(bid.getId()), eq("BID_AUTO_CANCELLED_TIMEOUT"), any(), any());

        ArgumentCaptor<BidRejectedEvent> captor = ArgumentCaptor.forClass(BidRejectedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getBidId()).isEqualTo(bid.getId());
        assertThat(captor.getValue().getReason()).isEqualTo("TRAVELER_NO_RESPONSE");
    }

    @Test
    void handles_multiple_bids() {
        BidEntity b1 = timedOutBid();
        BidEntity b2 = timedOutBid();
        when(bidRepository.findPendingTimedOut(any(), any(), any())).thenReturn(List.of(b1, b2));

        scheduler.autoCancelUnansweredBids();

        verify(bidRepository).save(b1);
        verify(bidRepository).save(b2);
        verify(eventPublisher, times(2)).publishEvent(any(BidRejectedEvent.class));
    }

    @Test
    void passes_correct_thresholds_to_repository() {
        when(bidRepository.findPendingTimedOut(any(), any(), any())).thenReturn(List.of());

        scheduler.autoCancelUnansweredBids();

        ArgumentCaptor<LocalDateTime> twentyFourCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDate> halfDayCaptor = ArgumentCaptor.forClass(LocalDate.class);
        ArgumentCaptor<LocalDateTime> graceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(bidRepository).findPendingTimedOut(
                twentyFourCaptor.capture(), halfDayCaptor.capture(), graceCaptor.capture());

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 24h threshold ≈ now - 24h
        assertThat(twentyFourCaptor.getValue()).isBetween(now.minusHours(24).minusMinutes(1), now.minusHours(24).plusMinutes(1));
        // grace threshold ≈ now - 120 minutes
        assertThat(graceCaptor.getValue()).isBetween(now.minusMinutes(121), now.minusMinutes(119));
        // half-day threshold = (now + 12h).toLocalDate()
        assertThat(halfDayCaptor.getValue()).isEqualTo(now.plusHours(12).toLocalDate());
    }

    @Test
    void no_op_when_nothing_timed_out() {
        when(bidRepository.findPendingTimedOut(any(), any(), any())).thenReturn(List.of());

        scheduler.autoCancelUnansweredBids();

        verifyNoInteractions(auditService);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── deadlineFor : échéance annoncée au voyageur (FLUTTER-H7) ──────────────

    private static final LocalDateTime PENDING_SINCE = LocalDateTime.of(2026, 10, 9, 10, 0);

    @Test
    void deadlineFor_farDeparture_is24hAfterPendingSince() {
        assertThat(BidTimeoutScheduler.deadlineFor(PENDING_SINCE, LocalDate.of(2026, 10, 20)))
                .isEqualTo(PENDING_SINCE.plusHours(24));
    }

    @Test
    void deadlineFor_noDepartureDate_is24hAfterPendingSince() {
        assertThat(BidTimeoutScheduler.deadlineFor(PENDING_SINCE, null))
                .isEqualTo(PENDING_SINCE.plusHours(24));
    }

    @Test
    void deadlineFor_departureTomorrow_isH12BeforeDepartureDay() {
        // Départ le 10 : seuil H-12 = 9 octobre 12:00, avant les 24 h (10 octobre 10:00).
        assertThat(BidTimeoutScheduler.deadlineFor(PENDING_SINCE, LocalDate.of(2026, 10, 10)))
                .isEqualTo(LocalDateTime.of(2026, 10, 9, 12, 0));
    }

    @Test
    void deadlineFor_departureThresholdAlreadyPassed_neverBeforeGrace() {
        assertThat(BidTimeoutScheduler.deadlineFor(PENDING_SINCE, LocalDate.of(2026, 10, 9)))
                .isEqualTo(PENDING_SINCE.plusMinutes(BidTimeoutScheduler.MIN_GRACE_MINUTES));
    }
}
