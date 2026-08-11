package com.yadony.api.auth;

import com.yadony.api.auth.events.UserSuspendedEvent;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.kyc.KycRepository;
import com.yadony.api.payments.PaymentRepository;
import com.yadony.api.payments.wallet.WalletAccountEntity;
import com.yadony.api.payments.wallet.WalletAccountRepository;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — tests unitaires")
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PaymentRepository paymentRepository;
    @Mock private WalletAccountRepository walletAccountRepository;
    @Mock private KycRepository kycRepository;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private UserService userService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String FIREBASE_UID = "uid-user-001";

    private UserEntity user;

    @BeforeEach
    void setUp() throws Exception {
        user = new UserEntity();
        setId(user, USER_ID);
        setField(user, "firebaseUid", FIREBASE_UID);
        setField(user, "status", UserStatus.ACTIVE);
        setField(user, "refusedCount", 0);
    }

    @Nested
    @DisplayName("checkAndSuspendSender()")
    class CheckAndSuspendTests {

        @Test
        @DisplayName("refusedCount >= 2 → suspension + event publié")
        void checkAndSuspend_thresholdReached_suspends() throws Exception {
            setField(user, "refusedCount", 2);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            userService.checkAndSuspendSender(USER_ID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
            verify(userRepository).save(user);
            ArgumentCaptor<UserSuspendedEvent> captor = ArgumentCaptor.forClass(UserSuspendedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("refusedCount < 2 → pas de suspension")
        void checkAndSuspend_belowThreshold_noSuspension() throws Exception {
            setField(user, "refusedCount", 1);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            userService.checkAndSuspendSender(USER_ID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("déjà suspendu → aucune action")
        void checkAndSuspend_alreadySuspended_noOp() throws Exception {
            setField(user, "status", UserStatus.SUSPENDED);
            setField(user, "refusedCount", 3);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

            userService.checkAndSuspendSender(USER_ID);

            verify(userRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Nested
    @DisplayName("deleteAccount() — RGPD (délègue à requestDeletion)")
    class DeleteAccountTests {

        @Test
        @DisplayName("utilisateur sans transaction active → statut PENDING_DELETION + event publié")
        void deleteAccount_noActiveTransactions_pseuyadonymizes() throws Exception {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
            when(userRepository.save(any())).thenReturn(user);

            userService.deleteAccount(FIREBASE_UID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
            assertThat(user.getDeletionRequestedAt()).isNotNull();
            verify(eventPublisher).publishEvent(any(com.yadony.api.auth.events.AccountDeletionRequestedEvent.class));
        }

        @Test
        @DisplayName("transaction active (ESCROW) → 422 UNPROCESSABLE_ENTITY")
        void deleteAccount_activeEscrow_throws422() throws Exception {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(true);

            assertThatThrownBy(() -> userService.deleteAccount(FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND")
        void deleteAccount_unknownUser_throws404() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.deleteAccount(FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("solde wallet positif → 422 wallet-balance-not-empty")
        void deleteAccount_positiveWalletBalance_throws422() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
            WalletAccountEntity wallet = new WalletAccountEntity();
            wallet.setBalance(java.math.BigDecimal.TEN);
            when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, "EUR")).thenReturn(Optional.of(wallet));

            assertThatThrownBy(() -> userService.deleteAccount(FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("wallet à solde zéro → pas bloqué par le check wallet")
        void deleteAccount_zeroWalletBalance_doesNotBlock() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
            WalletAccountEntity wallet = new WalletAccountEntity();
            wallet.setBalance(java.math.BigDecimal.ZERO);
            when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, "EUR")).thenReturn(Optional.of(wallet));
            when(userRepository.save(any())).thenReturn(user);

            userService.deleteAccount(FIREBASE_UID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_DELETION);
        }
    }

    @Nested
    @DisplayName("checkDeletionEligibility() — lecture seule, sans exception")
    class CheckDeletionEligibilityTests {

        @Test
        @DisplayName("aucun blocage → canDelete=true, blockedReasonCode=null")
        void noBlockers_returnsEligible() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
            when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, "EUR")).thenReturn(Optional.empty());

            var result = userService.checkDeletionEligibility(FIREBASE_UID);

            assertThat(result.canDelete()).isTrue();
            assertThat(result.blockedReasonCode()).isNull();
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("escrow actif → canDelete=false, blockedReasonCode=active-transactions")
        void activeEscrow_returnsBlocked() {
            // Aucun stub wallet : l'absence d'UnnecessaryStubbing prouve que l'escrow est
            // vérifié en premier et court-circuite le check wallet (même ordre que requestDeletion).
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(true);

            var result = userService.checkDeletionEligibility(FIREBASE_UID);

            assertThat(result.canDelete()).isFalse();
            assertThat(result.blockedReasonCode()).isEqualTo("active-transactions");
        }

        @Test
        @DisplayName("solde wallet positif → canDelete=false, blockedReasonCode=wallet-balance-not-empty")
        void positiveWalletBalance_returnsBlocked() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.of(user));
            when(paymentRepository.hasActiveEscrowForUser(USER_ID)).thenReturn(false);
            WalletAccountEntity wallet = new WalletAccountEntity();
            wallet.setBalance(java.math.BigDecimal.TEN);
            when(walletAccountRepository.findByUserIdAndCurrency(USER_ID, "EUR")).thenReturn(Optional.of(wallet));

            var result = userService.checkDeletionEligibility(FIREBASE_UID);

            assertThat(result.canDelete()).isFalse();
            assertThat(result.blockedReasonCode()).isEqualTo("wallet-balance-not-empty");
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 NOT_FOUND")
        void unknownUser_throws404() {
            when(userRepository.findByFirebaseUid(FIREBASE_UID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.checkDeletionEligibility(FIREBASE_UID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("unsuspendUser()")
    class UnsuspendTests {

        @Test
        @DisplayName("utilisateur suspendu → status ACTIVE")
        void unsuspend_suspendedUser_activates() throws Exception {
            setField(user, "status", UserStatus.SUSPENDED);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            userService.unsuspendUser(USER_ID);

            assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
            verify(userRepository).save(user);
            verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_UNSUSPENDED"), any(), any());
        }

        @Test
        @DisplayName("utilisateur inconnu → 404")
        void unsuspend_unknownUser_throws404() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> userService.unsuspendUser(USER_ID))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("setCommissionRateOverride()")
    class SetCommissionRateOverrideTests {

        @Test
        @DisplayName("taux valide → enregistré + audit")
        void validRate_persistsAndAudits() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            userService.setCommissionRateOverride(USER_ID, new java.math.BigDecimal("0.08"));

            assertThat(user.getCommissionRateOverride()).isEqualByComparingTo("0.08");
            verify(userRepository).save(user);
            verify(auditService).log(eq("USER"), eq(USER_ID),
                    eq("USER_COMMISSION_RATE_OVERRIDE_SET"), eq(USER_ID), any());
        }

        @Test
        @DisplayName("null → retour au taux global (autorisé)")
        void nullRate_clearsOverride() {
            user.setCommissionRateOverride(new java.math.BigDecimal("0.08"));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            userService.setCommissionRateOverride(USER_ID, null);

            assertThat(user.getCommissionRateOverride()).isNull();
            verify(userRepository).save(user);
            verify(auditService).log(eq("USER"), eq(USER_ID),
                    eq("USER_COMMISSION_RATE_OVERRIDE_SET"), eq(USER_ID), any());
        }

        @Test
        @DisplayName("taux négatif → 422 invalid-commission-rate, pas de save")
        void negativeRate_throws422() {
            assertThatThrownBy(() ->
                    userService.setCommissionRateOverride(USER_ID, new java.math.BigDecimal("-0.01")))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        assertThat(((YadonyBusinessException) e).getStatus())
                                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                        assertThat(((YadonyBusinessException) e).getErrorCode())
                                .isEqualTo("invalid-commission-rate");
                    });
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("taux ≥ 1 → 422 invalid-commission-rate, pas de save")
        void rateAtOrAboveOne_throws422() {
            assertThatThrownBy(() ->
                    userService.setCommissionRateOverride(USER_ID, java.math.BigDecimal.ONE))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                            .isEqualTo("invalid-commission-rate"));
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("utilisateur inconnu → 404 user-not-found")
        void unknownUser_throws404() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    userService.setCommissionRateOverride(USER_ID, new java.math.BigDecimal("0.10")))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> {
                        assertThat(((YadonyBusinessException) e).getStatus())
                                .isEqualTo(HttpStatus.NOT_FOUND);
                        assertThat(((YadonyBusinessException) e).getErrorCode())
                                .isEqualTo("user-not-found");
                    });
        }
    }

    @Nested
    @DisplayName("Suspension de publication (D4)")
    class PublishingSuspensionTests {

        @Test
        @DisplayName("suspendPublishing → flag posé + audit")
        void suspendPublishing_setsFlag() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            userService.suspendPublishing(USER_ID, "retour non rendu");

            assertThat(user.isPublishingSuspended()).isTrue();
            assertThat(user.getPublishingSuspendedReason()).isEqualTo("retour non rendu");
            verify(auditService).log(eq("USER"), eq(USER_ID),
                    eq("TRAVELER_PUBLISHING_SUSPENDED"), eq(USER_ID), anyMap());
        }

        @Test
        @DisplayName("liftPublishingSuspension → flag levé")
        void liftPublishingSuspension_clearsFlag() throws Exception {
            setField(user, "publishingSuspended", true);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any())).thenReturn(user);

            userService.liftPublishingSuspension(USER_ID);

            assertThat(user.isPublishingSuspended()).isFalse();
            verify(auditService).log(eq("USER"), eq(USER_ID),
                    eq("TRAVELER_PUBLISHING_SUSPENSION_LIFTED"), eq(USER_ID), anyMap());
        }

        @Test
        @DisplayName("suspendPublishing user introuvable → 404")
        void suspendPublishing_userNotFound() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> userService.suspendPublishing(USER_ID, "x"))
                    .isInstanceOf(YadonyBusinessException.class)
                    .satisfies(e -> assertThat(((YadonyBusinessException) e).getStatus())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────

    private static void setId(Object obj, UUID id) throws Exception {
        Field f = obj.getClass().getSuperclass().getDeclaredField("id");
        f.setAccessible(true);
        f.set(obj, id);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f;
        try {
            f = obj.getClass().getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            f = obj.getClass().getSuperclass().getDeclaredField(name);
        }
        f.setAccessible(true);
        f.set(obj, value);
    }
}
