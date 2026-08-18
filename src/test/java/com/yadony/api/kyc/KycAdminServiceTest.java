package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import com.yadony.api.kyc.dto.KycAdminStatusResponse;
import com.yadony.api.notifications.NotificationDispatcher;
import com.stripe.model.identity.VerificationSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KycAdminService — consultation et réinitialisation KYC côté admin")
class KycAdminServiceTest {

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock NotificationDispatcher notificationDispatcher;

    KycAdminService service;

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new KycAdminService(kycRepository, userRepository, auditService, notificationDispatcher);
    }

    private UserEntity buildUser(KycStatus status) {
        UserEntity u = new UserEntity();
        setId(u, UUID.randomUUID());
        u.setFirebaseUid("uid-001");
        u.setKycStatus(status);
        return u;
    }

    private KycVerificationEntity buildKyc(UUID userId, KycVerificationStatus status, String sessionId) {
        KycVerificationEntity kyc = new KycVerificationEntity();
        setId(kyc, UUID.randomUUID());
        kyc.setUserId(userId);
        kyc.setStatus(status);
        kyc.setStripeVerificationSessionId(sessionId);
        return kyc;
    }

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ── getForUser ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("utilisateur introuvable → 404 user-not-found")
    void getForUser_userNotFound_throws404() {
        UUID unknown = UUID.randomUUID();
        when(userRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForUser(unknown))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException y = (YadonyBusinessException) e;
                    assertThat(y.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(y.getErrorCode()).isEqualTo("user-not-found");
                });
    }

    @Test
    @DisplayName("aucune ligne KYC → NOT_STARTED des deux côtés, Stripe non interrogé")
    void getForUser_noKycRow_returnsNotStarted() {
        UserEntity user = buildUser(KycStatus.NOT_STARTED);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        KycAdminStatusResponse resp = service.getForUser(user.getId());

        assertThat(resp.kycStatus()).isEqualTo("NOT_STARTED");
        assertThat(resp.verificationStatus()).isEqualTo("NOT_STARTED");
        assertThat(resp.stripeSessionId()).isNull();
        assertThat(resp.stripeStatus()).isNull();
        // Absence de session ≠ indisponibilité Stripe.
        assertThat(resp.stripeUnavailable()).isFalse();
    }

    @Test
    @DisplayName("session courante enrichie par l'appel live Stripe")
    void getForUser_enrichesWithStripeSession() {
        UserEntity user = buildUser(KycStatus.REJECTED);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.REJECTED, "vs_001");
        kyc.setRejectionReason("document_expired");
        kyc.setRejectionCode("document_expired");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));

        try (MockedStatic<VerificationSession> vs = mockStatic(VerificationSession.class)) {
            VerificationSession session = mock(VerificationSession.class);
            VerificationSession.LastError lastError = mock(VerificationSession.LastError.class);
            when(session.getStatus()).thenReturn("requires_input");
            when(session.getCreated()).thenReturn(1_770_000_000L);
            when(session.getLastError()).thenReturn(lastError);
            when(lastError.getCode()).thenReturn("document_expired");
            when(lastError.getReason()).thenReturn("The document has expired.");
            vs.when(() -> VerificationSession.retrieve("vs_001")).thenReturn(session);

            KycAdminStatusResponse resp = service.getForUser(user.getId());

            assertThat(resp.kycStatus()).isEqualTo("REJECTED");
            assertThat(resp.verificationStatus()).isEqualTo("REJECTED");
            assertThat(resp.stripeSessionId()).isEqualTo("vs_001");
            assertThat(resp.stripeStatus()).isEqualTo("requires_input");
            assertThat(resp.stripeLastErrorCode()).isEqualTo("document_expired");
            assertThat(resp.stripeLastErrorReason()).isEqualTo("The document has expired.");
            assertThat(resp.stripeCreatedAt()).isNotNull();
            assertThat(resp.stripeUnavailable()).isFalse();
        }
    }

    @Test
    @DisplayName("Stripe injoignable → état local + stripeUnavailable, jamais d'exception")
    void getForUser_stripeFails_degradesGracefully() {
        UserEntity user = buildUser(KycStatus.PENDING);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.PENDING, "vs_001");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));

        try (MockedStatic<VerificationSession> vs = mockStatic(VerificationSession.class)) {
            vs.when(() -> VerificationSession.retrieve("vs_001"))
                    .thenThrow(new RuntimeException("stripe down"));

            KycAdminStatusResponse resp = service.getForUser(user.getId());

            assertThat(resp.stripeUnavailable()).isTrue();
            assertThat(resp.stripeStatus()).isNull();
            assertThat(resp.kycStatus()).isEqualTo("PENDING");
            assertThat(resp.stripeSessionId()).isEqualTo("vs_001");
        }
    }

    // ── resetForUser ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("reset : UPDATE en place et resynchronisation des DEUX enums de statut")
    void resetForUser_updatesRowInPlaceAndSyncsBothEnums() throws Exception {
        UserEntity user = buildUser(KycStatus.REJECTED);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.REJECTED, "vs_001");
        kyc.setRejectionReason("document_expired");
        kyc.setRejectionCode("document_expired");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));

        try (MockedStatic<VerificationSession> vs = mockStatic(VerificationSession.class)) {
            VerificationSession session = mock(VerificationSession.class);
            vs.when(() -> VerificationSession.retrieve("vs_001")).thenReturn(session);

            KycAdminStatusResponse resp = service.resetForUser(user.getId(), ADMIN_ID, "document illisible");

            assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);
            assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
            assertThat(kyc.getStripeVerificationSessionId()).isNull();
            assertThat(kyc.getRejectionReason()).isNull();
            assertThat(kyc.getRejectionCode()).isNull();
            // La ligne n'est JAMAIS soft-deletée : uq_kyc_user_id est une contrainte UNIQUE
            // classique, une recréation ultérieure la violerait.
            assertThat(kyc.getDeletedAt()).isNull();
            assertThat(resp.kycStatus()).isEqualTo("NOT_STARTED");
            assertThat(resp.stripeSessionId()).isNull();

            verify(kycRepository).save(kyc);
            verify(userRepository).save(user);
            verify(session).cancel();
        }
    }

    @Test
    @DisplayName("reset : audit KYC_RESET_BY_ADMIN + notification à l'utilisateur")
    void resetForUser_auditsAndNotifies() {
        UserEntity user = buildUser(KycStatus.VERIFIED);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.VERIFIED, null);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));

        service.resetForUser(user.getId(), ADMIN_ID, "fraude suspectée");

        verify(auditService).log(eq("kyc_verification"), eq(kyc.getId()), eq("KYC_RESET_BY_ADMIN"),
                eq(ADMIN_ID), any());
        verify(notificationDispatcher).notifyUser(eq(user.getId()), any(), any(), any());
    }

    @Test
    @DisplayName("reset : l'échec de l'annulation Stripe ne bloque pas la remise à zéro locale")
    void resetForUser_stripeCancelFails_stillResetsLocally() {
        UserEntity user = buildUser(KycStatus.PENDING);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.PENDING, "vs_001");
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));

        try (MockedStatic<VerificationSession> vs = mockStatic(VerificationSession.class)) {
            vs.when(() -> VerificationSession.retrieve("vs_001"))
                    .thenThrow(new RuntimeException("stripe down"));

            service.resetForUser(user.getId(), ADMIN_ID, "motif");

            assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);
            assertThat(kyc.getStripeVerificationSessionId()).isNull();
        }
    }

    @Test
    @DisplayName("reset : aucune ligne KYC → 422 kyc-not-started")
    void resetForUser_noKycRow_throws422() {
        UserEntity user = buildUser(KycStatus.NOT_STARTED);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetForUser(user.getId(), ADMIN_ID, "motif"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException y = (YadonyBusinessException) e;
                    assertThat(y.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                    assertThat(y.getErrorCode()).isEqualTo("kyc-not-started");
                });
    }
}
