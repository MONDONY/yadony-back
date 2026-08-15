package com.yadony.api.cancellation.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.cancellation.events.ReturnDeadlineExpiredEvent;
import com.yadony.api.cancellation.events.ReturnDeadlineWarningEvent;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.matching.BidEntity;
import com.yadony.api.matching.BidRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/** Job J+3 (D4) — lève une alerte admin, publie l'event, ne suspend jamais. */
@ExtendWith(MockitoExtension.class)
class ReturnDeadlineSchedulerTest {

    @Mock private BidRepository bidRepository;
    @Mock private AdminAlertRepository adminAlertRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AnnouncementRepository announcementRepository;

    @InjectMocks private ReturnDeadlineScheduler scheduler;

    private BidEntity expiredBid(UUID id) {
        BidEntity b = new BidEntity();
        ReflectionTestUtils.setField(b, "id", id);
        ReflectionTestUtils.setField(b, "senderId", UUID.randomUUID());
        b.setReturnDeadline(LocalDateTime.now().minusHours(1));
        return b;
    }

    @Test
    void expired_raises_admin_alert_and_publishes_event() {
        UUID bidId = UUID.randomUUID();
        when(bidRepository.findByReturnDeadlineBeforeAndReturnedAtIsNullAndReturnExpiredNotifiedAtIsNull(any()))
                .thenReturn(List.of(expiredBid(bidId)));
        scheduler.run();

        ArgumentCaptor<AdminAlertEntity> alertCap = ArgumentCaptor.forClass(AdminAlertEntity.class);
        verify(adminAlertRepository).save(alertCap.capture());
        assertThat(alertCap.getValue().getPayload()).contains(bidId.toString());

        ArgumentCaptor<ReturnDeadlineExpiredEvent> evCap =
                ArgumentCaptor.forClass(ReturnDeadlineExpiredEvent.class);
        verify(eventPublisher).publishEvent(evCap.capture());
        assertThat(evCap.getValue().bidId()).isEqualTo(bidId);
    }

    @Test
    void idempotent_skips_when_expiration_notification_was_already_marked() {
        UUID bidId = UUID.randomUUID();
        BidEntity bid = expiredBid(bidId);
        ReflectionTestUtils.setField(bid, "returnExpiredNotifiedAt", LocalDateTime.now());
        when(bidRepository.findByReturnDeadlineBeforeAndReturnedAtIsNullAndReturnExpiredNotifiedAtIsNull(any()))
                .thenReturn(List.of(bid));

        scheduler.run();

        verify(adminAlertRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void no_expired_is_noop() {
        when(bidRepository.findByReturnDeadlineBeforeAndReturnedAtIsNullAndReturnExpiredNotifiedAtIsNull(any()))
                .thenReturn(List.of());

        scheduler.run();

        verifyNoInteractions(eventPublisher);
        verify(adminAlertRepository, never()).save(any());
    }

    @Test
    void deadlineWithinOneDay_publishesSingleWarningAndMarksBid() {
        UUID bidId = UUID.randomUUID();
        UUID announcementId = UUID.randomUUID();
        UUID travelerId = UUID.randomUUID();
        BidEntity bid = expiredBid(bidId);
        bid.setReturnDeadline(LocalDateTime.now().plusHours(20));
        ReflectionTestUtils.setField(bid, "announcementId", announcementId);
        AnnouncementEntity announcement = new AnnouncementEntity();
        ReflectionTestUtils.setField(announcement, "travelerId", travelerId);
        when(bidRepository.findByReturnDeadlineBetweenAndReturnWarningSentAtIsNullAndReturnedAtIsNull(
                any(), any())).thenReturn(List.of(bid));
        when(bidRepository.findByReturnDeadlineBeforeAndReturnedAtIsNullAndReturnExpiredNotifiedAtIsNull(any()))
                .thenReturn(List.of());
        when(announcementRepository.findById(announcementId)).thenReturn(Optional.of(announcement));

        scheduler.run();

        ArgumentCaptor<ReturnDeadlineWarningEvent> eventCaptor =
                ArgumentCaptor.forClass(ReturnDeadlineWarningEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().bidId()).isEqualTo(bidId);
        assertThat(eventCaptor.getValue().travelerId()).isEqualTo(travelerId);
        assertThat(bid.getReturnWarningSentAt()).isNotNull();
        verify(bidRepository).save(bid);
    }
}
