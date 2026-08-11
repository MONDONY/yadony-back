package com.yadony.api.auth;

import com.yadony.api.auth.events.AccountDeletionRequestedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — suppression de compte")
class UserServiceDeleteAccountTest {

    @Mock private UserRepository userRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private WalletAccountRepository walletAccountRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AccountFinalizationService accountFinalizationService;

    @InjectMocks private UserService userService;

    private static final String FIREBASE_UID = "uid-test-001";
    private static final UUID USER_ID = UUID.randomUUID();

    private UserEntity makeUser(UserStatus status) {
        UserEntity u = new UserEntity();
        setId(u, USER_ID);
        u.setFirebaseUid(FIREBASE_UID);
        u.setFirstName("Jean");
        u.setLastName("Dupont");
        u.setStatus(status);
        return u;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Class<?> c = entity.getClass();
            while (c != null) {
                try {
                    Field f = c.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("requestDeletion")
    class RequestDeletion {

        @Test
        @DisplayName("ESCROW actif → 422")
        void escrowActive_throws422() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(true);

            assertThatThrownBy(() -> userService.requestDeletion(FIREBASE_UID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("succès → statut PENDING_DELETION, deletionRequestedAt set, event publié")
        void success_setsStatusAndPublishesEvent() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.requestDeletion(FIREBASE_UID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
            assertThat(user.getDeletionRequestedAt()).isNotNull();

            ArgumentCaptor<AccountDeletionRequestedEvent> captor =
                ArgumentCaptor.forClass(AccountDeletionRequestedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("solde wallet positif → 422 wallet-balance-not-empty")
        void positiveWalletBalance_throws422() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
            WalletAccountEntity wallet = new WalletAccountEntity();
            wallet.setBalance(java.math.BigDecimal.TEN);
            when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, "EUR")).thenReturn(Optional.of(wallet));

            assertThatThrownBy(() -> userService.requestDeletion(FIREBASE_UID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("déjà PENDING_DELETION → idempotent, event non re-publié")
        void alreadyPending_idempotent() {
            UserEntity user = makeUser(UserStatus.PENDING_DELETION);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            userService.requestDeletion(FIREBASE_UID);

            verify(eventPublisher, never()).publishEvent(any());
            verify(userRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("reactivateAccount")
    class Reactivate {

        @Test
        @DisplayName("PENDING_DELETION → ACTIVE, deletionRequestedAt null, audit log")
        void success_reactivates() {
            UserEntity user = makeUser(UserStatus.PENDING_DELETION);
            user.setDeletionRequestedAt(Instant.now().minusSeconds(3600));
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            userService.reactivateAccount(FIREBASE_UID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            assertThat(user.getDeletionRequestedAt()).isNull();
            verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_DELETION_CANCELLED"), eq(USER_ID), any());
        }

        @Test
        @DisplayName("statut != PENDING_DELETION → 409")
        void notPending_throws409() {
            UserEntity user = makeUser(UserStatus.ACTIVE);
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> userService.reactivateAccount(FIREBASE_UID))
                .isInstanceOf(YadonyBusinessException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    @DisplayName("finalizeGdprDeletion")
    class FinalizeGdpr {

        @Test
        @DisplayName("délègue à AccountFinalizationService avec SOFT_GRACE_EXPIRED")
        void delegatesToFinalizationService() {
            UserEntity user = makeUser(UserStatus.PENDING_DELETION);

            userService.finalizeGdprDeletion(user);

            verify(accountFinalizationService).finalize(user, FinalizationReason.SOFT_GRACE_EXPIRED);
        }
    }
}
