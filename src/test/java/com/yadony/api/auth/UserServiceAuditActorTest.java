package com.yadony.api.auth;

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

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code audit_log} est IMMUABLE : une trace fausse ne pourra jamais etre corrigee.
 *
 * <p>Le 4e argument de {@link AuditService#log} est l'<b>acteur</b>. Les gestes
 * d'administration sur un compte y passaient l'utilisateur CIBLE : la trace disait que
 * l'utilisateur s'etait suspendu, banni ou coupe la messagerie lui-meme, et l'administrateur
 * responsable n'apparaissait nulle part.
 *
 * <p>Les tests existants passaient parce qu'ils verifiaient cet argument avec {@code any()} —
 * ils n'affirmaient rien sur l'identite de l'acteur. Ceux-ci l'affirment.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceAuditActorTest — l'acteur audite est l'administrateur, jamais la cible")
class UserServiceAuditActorTest {

    @Mock UserRepository userRepository;
    @Mock com.yadony.api.payments.PaymentRepository paymentRepository;
    @Mock com.yadony.api.payments.wallet.WalletAccountRepository walletAccountRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AccountFinalizationService accountFinalizationService;
    @Mock FirestoreService firestoreService;
    @Mock NotificationDispatcher notificationDispatcher;

    UserService service;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, paymentRepository, walletAccountRepository,
                auditService, eventPublisher, accountFinalizationService,
                firestoreService, notificationDispatcher);

        UserEntity user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setFirebaseUid("uid-cible");
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any(UserEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("Couper la messagerie : l'acteur est l'administrateur")
    void muteMessaging_recordsTheAdminAsActor() {
        service.muteMessaging(USER_ID, 24, "harcelement", ADMIN_ID);

        verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_MESSAGING_MUTED"),
                eq(ADMIN_ID), anyMap());
    }

    @Test
    @DisplayName("Retablir la messagerie : l'acteur est l'administrateur")
    void unmuteMessaging_recordsTheAdminAsActor() {
        service.unmuteMessaging(USER_ID, ADMIN_ID);

        verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_MESSAGING_UNMUTED"),
                eq(ADMIN_ID), anyMap());
    }

    @Test
    @DisplayName("Suspendre un compte : l'acteur est l'administrateur")
    void suspendUser_recordsTheAdminAsActor() {
        service.suspendUser(USER_ID, "fraude", ADMIN_ID);

        verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_SUSPENDED_BY_ADMIN"),
                eq(ADMIN_ID), anyMap());
    }

    @Test
    @DisplayName("Bannir un compte : l'acteur est l'administrateur")
    void banUser_recordsTheAdminAsActor() {
        service.banUser(USER_ID, "recidive", ADMIN_ID);

        verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_BANNED_BY_ADMIN"),
                eq(ADMIN_ID), anyMap());
    }
}
