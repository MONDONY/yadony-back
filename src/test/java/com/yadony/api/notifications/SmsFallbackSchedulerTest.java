package com.yadony.api.notifications;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SmsFallbackSchedulerTest {

    @Mock NotificationRepository notificationRepository;
    @Mock UserRepository userRepository;
    @Mock SmsService smsService;
    @Mock com.yadony.api.auth.FirebaseContactService firebaseContact;

    SmsFallbackScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new SmsFallbackScheduler(
                notificationRepository, userRepository, smsService, firebaseContact);
    }

    private UserEntity user(UUID userId, String uid) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid(uid);
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", userId);
        return u;
    }

    /**
     * Les users et leurs coordonnées Firebase sont résolus en un lot avant la boucle
     * d'envoi, pas notification par notification.
     */
    private void stubBatch(List<UserEntity> users,
                           Map<String, com.yadony.api.auth.FirebaseContactService.Contact> contacts) {
        when(userRepository.findAllById(any())).thenReturn(users);
        when(firebaseContact.getContacts(any())).thenReturn(contacts);
    }

    private com.yadony.api.auth.FirebaseContactService.Contact phone(String phone) {
        return new com.yadony.api.auth.FirebaseContactService.Contact(phone, null);
    }

    @Test
    void noPendingFallbacks_doesNothing() {
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of());

        scheduler.processPendingFallbacks();

        verifyNoInteractions(smsService, userRepository);
    }

    @Test
    void pendingFallback_sendsSmsThenMarksSmsSentAt() {
        UUID userId = UUID.randomUUID();
        var notification = criticalNotification(userId);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(notification));

        stubBatch(List.of(user(userId, "uid-1")), Map.of("uid-1", phone("+221701234567")));

        scheduler.processPendingFallbacks();

        verify(smsService).send(eq("+221701234567"), contains("[Yadony]"));
        assertThat(notification.getSmsSentAt()).isNotNull();
    }

    @Test
    void pendingFallback_smsTextContainsTitleAndBody() {
        UUID userId = UUID.randomUUID();
        var notification = criticalNotification(userId);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(notification));

        stubBatch(List.of(user(userId, "uid-1")), Map.of("uid-1", phone("+221701234567")));

        scheduler.processPendingFallbacks();

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(smsService).send(any(), textCaptor.capture());
        assertThat(textCaptor.getValue()).contains("Livraison confirmée").contains("arrivé à destination");
    }

    @Test
    void pendingFallback_userHasNoPhone_marksHandledWithoutSms() {
        UUID userId = UUID.randomUUID();
        var notification = criticalNotification(userId);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(notification));

        // Aucun numéro côté Firebase (compte créé par email OTP par exemple)
        stubBatch(List.of(user(userId, "uid-sans-tel")), Map.of("uid-sans-tel", phone(null)));

        scheduler.processPendingFallbacks();

        verifyNoInteractions(smsService);
        assertThat(notification.getSmsSentAt()).isNotNull();
    }

    @Test
    void pendingFallback_userNotFound_marksHandled() {
        UUID userId = UUID.randomUUID();
        var notification = criticalNotification(userId);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(notification));
        stubBatch(List.of(), Map.of());

        scheduler.processPendingFallbacks();

        verifyNoInteractions(smsService);
        assertThat(notification.getSmsSentAt()).isNotNull();
    }

    @Test
    void multiplePendingFallbacks_processesAll() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        var n1 = criticalNotification(userId1);
        var n2 = criticalNotification(userId2);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(n1, n2));

        stubBatch(List.of(user(userId1, "uid-1"), user(userId2, "uid-2")),
                Map.of("uid-1", phone("+221111111111"), "uid-2", phone("+221222222222")));

        scheduler.processPendingFallbacks();

        verify(smsService, times(2)).send(anyString(), anyString());
        // Un seul aller-retour Firebase pour les deux notifications
        verify(firebaseContact, times(1)).getContacts(any());
    }

    @Test
    void carrierRejectsRecipient_marksHandled_noRetryLoop() {
        // InvalidSmsRecipientException sortait du lambda avant markSmsSent : la notification
        // restait « pending » et repartait chez le transporteur toutes les 30 s, pour rien.
        UUID userId = UUID.randomUUID();
        var notification = criticalNotification(userId);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(notification));
        stubBatch(List.of(user(userId, "uid-1")), Map.of("uid-1", phone("+22574884000")));
        doThrow(new InvalidSmsRecipientException(21211)).when(smsService).send(anyString(), anyString());

        scheduler.processPendingFallbacks();

        assertThat(notification.getSmsSentAt()).isNotNull();
    }

    @Test
    void smsServiceThrows_doesNotPropagateAndContinues() {
        UUID userId1 = UUID.randomUUID();
        UUID userId2 = UUID.randomUUID();
        var n1 = criticalNotification(userId1);
        var n2 = criticalNotification(userId2);
        when(notificationRepository.findPendingSmsFallbacks(any())).thenReturn(List.of(n1, n2));

        stubBatch(List.of(user(userId1, "uid-1"), user(userId2, "uid-2")),
                Map.of("uid-1", phone("+221111111111"), "uid-2", phone("+221222222222")));
        doThrow(new RuntimeException("SMS provider down")).when(smsService).send(anyString(), anyString());

        // Must not throw
        scheduler.processPendingFallbacks();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private NotificationEntity criticalNotification(UUID userId) {
        return new NotificationEntity(userId, "DELIVERY_CONFIRMED",
                "Livraison confirmée", "Votre colis est arrivé à destination",
                Map.of("type", "DELIVERY_CONFIRMED"), true);
    }
}