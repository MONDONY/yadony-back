package com.yadony.api.automation;

import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.matching.AnnouncementEntity;
import com.yadony.api.matching.AnnouncementRepository;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CapacityWatchSchedulerTest {

    @Mock private AutomationRuleRepository ruleRepository;
    @Mock private AnnouncementRepository announcementRepository;
    @Mock private AutomationCapacityWatermarkRepository watermarkRepository;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private AutomationActionExecutor executor;

    private CapacityWatchScheduler scheduler;
    private UUID travelerId, announcementId;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        scheduler = new CapacityWatchScheduler(ruleRepository, announcementRepository,
                watermarkRepository, notificationDispatcher, executor);
        travelerId = UUID.randomUUID();
        announcementId = UUID.randomUUID();
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    private AutomationRuleEntity capacityRule(Map<String, Object> action) {
        AutomationRuleEntity r = new AutomationRuleEntity();
        r.setTravelerId(travelerId);
        r.setPresetRuleId("alert_capacity_free");
        r.setEnabled(true);
        r.setAction(action);
        return r;
    }

    private AnnouncementEntity announcement(BigDecimal availableKg) {
        AnnouncementEntity a = new AnnouncementEntity();
        a.setTravelerId(travelerId);
        a.setAvailableKg(availableKg);
        try {
            var idField = AnnouncementEntity.class.getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(a, announcementId);
        } catch (Exception ignored) {}
        return a;
    }

    @Test
    void run_createsWatermarkOnFirstObservationAboveThreshold_withoutNotifying() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(capacityRule(Map.of("freedKgThreshold", 5, "consecutiveHours", 2))));
        when(announcementRepository.findActiveByTravelerId(travelerId))
                .thenReturn(List.of(announcement(new BigDecimal("10"))));
        when(watermarkRepository.findByAnnouncementId(announcementId)).thenReturn(Optional.empty());

        scheduler.checkCapacityWatermarks(List.of(travelerId));

        verify(watermarkRepository).save(argThat(w -> w.getFreeSince() != null));
        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }

    @Test
    void run_notifiesWhenThresholdHeldLongEnough() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(capacityRule(Map.of("freedKgThreshold", 5, "consecutiveHours", 2))));
        when(announcementRepository.findActiveByTravelerId(travelerId))
                .thenReturn(List.of(announcement(new BigDecimal("10"))));

        AutomationCapacityWatermarkEntity existing = new AutomationCapacityWatermarkEntity();
        existing.setAnnouncementId(announcementId);
        existing.setFreeSince(OffsetDateTime.now().minusHours(3));
        when(watermarkRepository.findByAnnouncementId(announcementId)).thenReturn(Optional.of(existing));

        scheduler.checkCapacityWatermarks(List.of(travelerId));

        verify(notificationDispatcher).notifyUser(eq(travelerId), any(), any(), any());
        verify(watermarkRepository).save(argThat(w -> w.getLastAlertedAt() != null));
    }

    @Test
    void run_doesNotReNotifyBeforeNextFreeWindow() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(capacityRule(Map.of("freedKgThreshold", 5, "consecutiveHours", 2))));
        when(announcementRepository.findActiveByTravelerId(travelerId))
                .thenReturn(List.of(announcement(new BigDecimal("10"))));

        OffsetDateTime freeSince = OffsetDateTime.now().minusHours(3);
        AutomationCapacityWatermarkEntity existing = new AutomationCapacityWatermarkEntity();
        existing.setAnnouncementId(announcementId);
        existing.setFreeSince(freeSince);
        existing.setLastAlertedAt(freeSince.plusMinutes(1));
        when(watermarkRepository.findByAnnouncementId(announcementId)).thenReturn(Optional.of(existing));

        scheduler.checkCapacityWatermarks(List.of(travelerId));

        verify(notificationDispatcher, never()).notifyUser(any(), any(), any(), any());
    }

    @Test
    void run_resetsWatermarkWhenCapacityDropsBelowThreshold() {
        when(ruleRepository.findByTravelerIdOrderByCreatedAtAsc(travelerId))
                .thenReturn(List.of(capacityRule(Map.of("freedKgThreshold", 5, "consecutiveHours", 2))));
        when(announcementRepository.findActiveByTravelerId(travelerId))
                .thenReturn(List.of(announcement(new BigDecimal("2"))));

        AutomationCapacityWatermarkEntity existing = new AutomationCapacityWatermarkEntity();
        existing.setAnnouncementId(announcementId);
        existing.setFreeSince(OffsetDateTime.now().minusHours(3));
        when(watermarkRepository.findByAnnouncementId(announcementId)).thenReturn(Optional.of(existing));

        scheduler.checkCapacityWatermarks(List.of(travelerId));

        verify(watermarkRepository).save(argThat(w ->
                w.getFreeSince() == null && w.getLastAlertedAt() == null));
    }
}
