package com.yadony.api.payments;

import com.yadony.api.auth.StripeAccountStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StripeOnboardingReminderSchedulerTest {

    @Mock private UserRepository userRepository;
    @Mock private NotificationDispatcher notificationDispatcher;
    @Mock private AuditService auditService;

    @InjectMocks private StripeOnboardingReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "firstDelay", Duration.ofDays(1));
        ReflectionTestUtils.setField(scheduler, "secondDelay", Duration.ofDays(7));
        ReflectionTestUtils.setField(scheduler, "enabled", true);
    }

    private UserEntity staleUser(Instant lastReminderAt) {
        UserEntity user = new UserEntity();
        PaymentServiceTestFactory.setId(user, UUID.randomUUID());
        user.setStripeAccountId("acct_test");
        user.setStripeAccountStatus(StripeAccountStatus.PENDING_ONBOARDING);
        user.setStripeAccountCreatedAt(Instant.now().minus(Duration.ofDays(10)));
        user.setStripeOnboardingLastReminderAt(lastReminderAt);
        return user;
    }

    @Test
    void sendsReminderAndStampsTheUser() {
        UserEntity user = staleUser(null);
        when(userRepository.findStaleConnectOnboardings(any(), any())).thenReturn(List.of(user));

        scheduler.remindStaleOnboardings();

        verify(notificationDispatcher).notifyUser(eq(user.getId()), anyString(), anyString(),
                eq(Map.of("type", "STRIPE_ONBOARDING_INCOMPLETE")));
        // L'horodatage est ce qui empêche le tick suivant de renvoyer la même
        // relance : sans lui le job spammerait toutes les heures.
        assertThat(user.getStripeOnboardingLastReminderAt()).isNotNull();
        verify(userRepository).save(user);
    }

    @Test
    void firstReminderIsAuditedAsAttemptOne() {
        UserEntity user = staleUser(null);
        when(userRepository.findStaleConnectOnboardings(any(), any())).thenReturn(List.of(user));

        scheduler.remindStaleOnboardings();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(eq("USER"), eq(user.getId()),
                eq("STRIPE_ONBOARDING_REMINDER_SENT"), eq(user.getId()), payload.capture());
        assertThat(payload.getValue()).containsEntry("attempt", 1);
    }

    @Test
    void secondReminderIsAuditedAsAttemptTwo() {
        UserEntity user = staleUser(Instant.now().minus(Duration.ofDays(8)));
        when(userRepository.findStaleConnectOnboardings(any(), any())).thenReturn(List.of(user));

        scheduler.remindStaleOnboardings();

        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(auditService).log(any(), any(), any(), any(), payload.capture());
        assertThat(payload.getValue()).containsEntry("attempt", 2);
    }

    /**
     * Le calcul des seuils est la seule chose que ce job décide : une erreur de
     * signe enverrait les relances à tout le monde, ou à personne.
     */
    @Test
    void queriesWithDelaysSubtractedFromNow() {
        when(userRepository.findStaleConnectOnboardings(any(), any())).thenReturn(List.of());
        Instant before = Instant.now();

        scheduler.remindStaleOnboardings();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> first = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> second = ArgumentCaptor.forClass(Instant.class);
        verify(userRepository).findStaleConnectOnboardings(first.capture(), second.capture());

        // Le scheduler lit l'horloge entre `before` et `after` : les seuils
        // doivent tomber dans cette fenêtre, décalés du délai correspondant.
        assertThat(first.getValue())
                .isBetween(before.minus(Duration.ofDays(1)), after.minus(Duration.ofDays(1)));
        assertThat(second.getValue())
                .isBetween(before.minus(Duration.ofDays(7)), after.minus(Duration.ofDays(7)));
        assertThat(second.getValue()).isBefore(first.getValue());
    }

    /**
     * Un push qui échoue ne doit pas laisser l'utilisateur éligible : mieux vaut
     * rater une relance que d'en envoyer une par heure.
     */
    @Test
    void keepsTheStampWhenTheNotificationFails() {
        UserEntity user = staleUser(null);
        when(userRepository.findStaleConnectOnboardings(any(), any())).thenReturn(List.of(user));
        doThrow(new IllegalStateException("FCM down"))
                .when(notificationDispatcher).notifyUser(any(), anyString(), anyString(), any());

        scheduler.remindStaleOnboardings();

        assertThat(user.getStripeOnboardingLastReminderAt()).isNotNull();
        verify(userRepository).save(user);
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void doesNothingWhenNobodyIsStale() {
        when(userRepository.findStaleConnectOnboardings(any(), any())).thenReturn(List.of());

        scheduler.remindStaleOnboardings();

        verifyNoInteractions(notificationDispatcher);
        verifyNoInteractions(auditService);
    }

    @Test
    void doesNotEvenQueryWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "enabled", false);

        scheduler.remindStaleOnboardings();

        verifyNoInteractions(userRepository);
        verifyNoInteractions(notificationDispatcher);
    }
}
