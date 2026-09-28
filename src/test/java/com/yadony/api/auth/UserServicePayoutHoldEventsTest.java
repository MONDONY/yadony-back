package com.yadony.api.auth;

import com.yadony.api.auth.events.UserBannedEvent;
import com.yadony.api.auth.events.UserReinstatedEvent;
import com.yadony.api.billing.ProSubscriptionRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Le gel des versements (payments/hold) ne connait le bannissement et sa levee que par ces deux
 * evenements : sans eux, un voyageur banni continuerait d'etre paye a la livraison.
 */
@ExtendWith(MockitoExtension.class)
class UserServicePayoutHoldEventsTest {

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
    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new UserService(userRepository, paymentRepository, walletAccountRepository,
                auditService, eventPublisher, accountFinalizationService,
                firestoreService, notificationDispatcher, walletRefundRequestService,
                walletSelfRefundService, proSubscriptionRepository);
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setFirebaseUid("uid-cible");
        lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        lenient().when(userRepository.save(any(UserEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void banUser_publieUserBannedEvent_avecMotifEtAdmin() {
        service.banUser(USER_ID, "fraude avérée", ADMIN_ID);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new UserBannedEvent(USER_ID, "fraude avérée", ADMIN_ID));
    }

    @Test
    void unsuspendUser_dunCompteBanni_publieUserReinstatedEvent() {
        user.setStatus(UserStatus.BANNED);

        service.unsuspendUser(USER_ID, ADMIN_ID);

        verify(eventPublisher).publishEvent(new UserReinstatedEvent(USER_ID, ADMIN_ID));
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void unsuspendUser_dunCompteSuspendu_nePublieRien() {
        user.setStatus(UserStatus.SUSPENDED);

        service.unsuspendUser(USER_ID, ADMIN_ID);

        verify(eventPublisher, never()).publishEvent(any(UserReinstatedEvent.class));
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }
}
