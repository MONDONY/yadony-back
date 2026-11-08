package com.yadony.api.auth;

import com.yadony.api.auth.events.UserSuspendedEvent;
import com.yadony.api.billing.ProSubscriptionRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Suspension décidée par le système (codes SMS demandés pour plusieurs numéros, 08/11/2026) :
 * même effet qu'une suspension par un admin, acteur {@code null} dans l'audit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService.suspendAutomatically")
class UserServiceAutoSuspendTest {

    @Mock UserRepository userRepository;
    @Mock com.yadony.api.payments.PaymentRepository paymentRepository;
    @Mock com.yadony.api.payments.wallet.WalletAccountRepository walletAccountRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AccountFinalizationService accountFinalizationService;
    @Mock FirestoreService firestoreService;
    @Mock NotificationDispatcher notificationDispatcher;
    @Mock com.yadony.api.payments.wallet.WalletRefundRequestService walletRefundRequestService;
    @Mock com.yadony.api.payments.wallet.WalletSelfRefundService walletSelfRefundService;
    @Mock ProSubscriptionRepository proSubscriptionRepository;

    UserService service;
    UserEntity user;

    private static final UUID USER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, paymentRepository, walletAccountRepository,
                auditService, eventPublisher, accountFinalizationService,
                firestoreService, notificationDispatcher, walletRefundRequestService,
                walletSelfRefundService, proSubscriptionRepository);
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any(UserEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("compte actif → SUSPENDED, audit sans acteur, événement de suspension")
    void activeAccount_isSuspended() {
        boolean suspended = service.suspendAutomatically(USER_ID, "sms_otp_multiple_numbers",
                "Suspension automatique", Map.of("distinctNumbers", 3));

        assertThat(suspended).isTrue();
        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_AUTO_SUSPENDED"), isNull(),
                argThat(payload -> "sms_otp_multiple_numbers".equals(payload.get("reason"))
                        && Integer.valueOf(3).equals(payload.get("distinctNumbers"))));
        verify(eventPublisher).publishEvent(argThat((Object e) ->
                e instanceof UserSuspendedEvent));
    }

    @Test
    @DisplayName("compte déjà suspendu → rien ne change, ni audit ni événement")
    void alreadySuspended_untouched() {
        user.setStatus(UserStatus.SUSPENDED);

        assertThat(service.suspendAutomatically(USER_ID, "sms_otp_multiple_numbers", "x", Map.of())).isFalse();

        verify(userRepository, never()).save(any());
        verifyNoInteractions(auditService, eventPublisher);
    }

    @Test
    @DisplayName("compte en cours de suppression → statut conservé")
    void pendingDeletion_untouched() {
        user.setStatus(UserStatus.PENDING_DELETION);

        assertThat(service.suspendAutomatically(USER_ID, "sms_otp_multiple_numbers", "x", Map.of())).isFalse();

        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("compte introuvable → faux, sans erreur")
    void unknownUser_false() {
        UUID other = UUID.randomUUID();
        when(userRepository.findById(other)).thenReturn(Optional.empty());

        assertThat(service.suspendAutomatically(other, "sms_otp_multiple_numbers", "x", Map.of())).isFalse();

        verifyNoInteractions(auditService, eventPublisher);
    }
}
