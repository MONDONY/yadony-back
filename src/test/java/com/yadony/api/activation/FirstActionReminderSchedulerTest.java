package com.yadony.api.activation;

import com.yadony.api.activation.dto.ActivationResponse.Opportunities;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.i18n.TestMessages;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FirstActionReminderSchedulerTest {

    @Mock ActivationRepository activationRepository;
    @Mock UserRepository userRepository;
    @Mock ActivationService activationService;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock AuditService auditService;
    @InjectMocks FirstActionReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "enabled", true);
        ReflectionTestUtils.setField(scheduler, "firstDelay", Duration.ofHours(24));
        ReflectionTestUtils.setField(scheduler, "secondDelay", Duration.ofHours(72));
        ReflectionTestUtils.setField(scheduler, "windowStartHour", 0);
        ReflectionTestUtils.setField(scheduler, "windowEndHour", 24);
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    private UserEntity candidate(String intent, int count) {
        UserEntity u = ActivationTestUsers.withId();
        u.setIntent(intent);
        u.setIntentDestinationCountry("CI");
        u.setCountry("FR");
        u.setFirstActionReminderCount(count);
        when(activationRepository.findFirstActionReminderCandidates(any(), any())).thenReturn(List.of(u.getId()));
        when(userRepository.findById(u.getId())).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void sendsFirstReminder_stampsAndAudits() {
        UserEntity u = candidate("SENDER", 0);
        when(activationService.opportunitiesFor(u)).thenReturn(new Opportunities("TRIPS", 3, List.of(), List.of()));

        scheduler.remind();

        assertThat(u.getFirstActionReminderCount()).isEqualTo(1);
        assertThat(u.getFirstActionReminderLastAt()).isNotNull();
        verify(userRepository).save(u);
        verify(notificationDispatcher).notifyUser(eq(u.getId()), anyString(), startsWith("3 trajets"),
                eq(Map.of("type", "FIRST_ACTION_REMINDER", "variant", "sender-trips")));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(u.getId()), eq("FIRST_ACTION_REMINDER_SENT"), eq(u.getId()),
                payload.capture());
        assertThat(payload.getValue()).containsEntry("attempt", 1).containsEntry("variant", "sender-trips");
    }

    @Test
    void secondReminder_isAttemptTwo_travelerWithoutPackages() {
        UserEntity u = candidate("TRAVELER", 1);
        when(activationService.opportunitiesFor(u)).thenReturn(Opportunities.none());

        scheduler.remind();

        assertThat(u.getFirstActionReminderCount()).isEqualTo(2);
        verify(notificationDispatcher).notifyUser(eq(u.getId()), anyString(), anyString(),
                eq(Map.of("type", "FIRST_ACTION_REMINDER", "variant", "traveler-none")));
    }

    @Test
    void skipsOutsideLocalWindow_withoutStamping() {
        ReflectionTestUtils.setField(scheduler, "windowEndHour", 0);   // fenêtre vide
        UserEntity u = candidate("SENDER", 0);

        scheduler.remind();

        assertThat(u.getFirstActionReminderCount()).isZero();
        assertThat(u.getFirstActionReminderLastAt()).isNull();
        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationDispatcher, auditService, activationService);
    }

    @Test
    void skipsVanishedUser() {
        UserEntity u = candidate("SENDER", 0);
        when(userRepository.findById(u.getId())).thenReturn(Optional.empty());

        scheduler.remind();

        verify(userRepository, never()).save(any());
        verifyNoInteractions(notificationDispatcher);
    }

    @Test
    void keepsStampWhenPushFails() {
        UserEntity u = candidate("SENDER", 0);
        when(activationService.opportunitiesFor(u)).thenReturn(Opportunities.none());
        doThrow(new IllegalStateException("FCM down")).when(notificationDispatcher)
                .notifyUser(any(), anyString(), anyString(), anyMap());

        scheduler.remind();

        assertThat(u.getFirstActionReminderCount()).isEqualTo(1);
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void queriesWithDelaysSubtractedFromNow() {
        when(activationRepository.findFirstActionReminderCandidates(any(), any())).thenReturn(List.of());
        Instant before = Instant.now();
        scheduler.remind();
        Instant after = Instant.now();
        ArgumentCaptor<Instant> first = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> second = ArgumentCaptor.forClass(Instant.class);
        verify(activationRepository).findFirstActionReminderCandidates(first.capture(), second.capture());
        assertThat(first.getValue()).isBetween(before.minus(Duration.ofHours(24)), after.minus(Duration.ofHours(24)));
        assertThat(second.getValue()).isBetween(before.minus(Duration.ofHours(72)), after.minus(Duration.ofHours(72)));
        verifyNoInteractions(userRepository, notificationDispatcher);
    }

    @Test
    void doesNotQueryWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);
        scheduler.remind();
        verifyNoInteractions(activationRepository);
    }

    @Test
    void variantOf_coversAllCases() {
        var none = Opportunities.none();
        var some = new Opportunities("TRIPS", 2, List.of(), List.of());
        assertThat(FirstActionReminderScheduler.variantOf(null, some)).isEqualTo("unknown");
        assertThat(FirstActionReminderScheduler.variantOf("SENDER", some)).isEqualTo("sender-trips");
        assertThat(FirstActionReminderScheduler.variantOf("BOTH", none)).isEqualTo("sender-none");
        assertThat(FirstActionReminderScheduler.variantOf("TRAVELER", some)).isEqualTo("traveler-packages");
        assertThat(FirstActionReminderScheduler.variantOf("TRAVELER", none)).isEqualTo("traveler-none");
    }
}
