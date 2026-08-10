package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyNotFoundException;
import com.yadony.api.kyc.dto.KycSessionResponse;
import com.yadony.api.kyc.dto.KycStatusResponse;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import com.stripe.model.identity.VerificationSession;
import com.stripe.param.identity.VerificationSessionCreateParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KycServiceTest {

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;

    KycService service;

    @BeforeEach
    void setUp() {
        service = new KycService(kycRepository, userRepository, auditService, eventPublisher);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserEntity buildUser(KycStatus kycStatus) {
        UserEntity user = new UserEntity();
        setId(user, UUID.randomUUID());
        user.setFirebaseUid("uid-001");
        user.setKycStatus(kycStatus);
        return user;
    }

    private KycVerificationEntity buildKyc(UUID userId, KycVerificationStatus status) {
        KycVerificationEntity kyc = new KycVerificationEntity();
        setId(kyc, UUID.randomUUID());
        kyc.setUserId(userId);
        kyc.setStripeVerificationSessionId("vs_test_001");
        kyc.setStatus(status);
        return kyc;
    }

    private void setId(Object entity, UUID id) {
        try {
            Class<?> clazz = entity.getClass().getSuperclass();
            Field f = clazz.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ── getStatus ─────────────────────────────────────────────────────────────

    @Test
    void getStatus_userNotFound_throwsNotFoundException() {
        when(userRepository.findByFirebaseUid("unknown")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getStatus("unknown"))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void getStatus_noKycRecord_returnsNotStarted() {
        UserEntity user = buildUser(KycStatus.PENDING);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());

        KycStatusResponse resp = service.getStatus("uid-001");

        assertThat(resp.kycStatus()).isEqualTo("PENDING");
        assertThat(resp.verificationStatus()).isEqualTo("NOT_STARTED");
    }

    @Test
    void getStatus_withVerifiedKycRecord_returnsVerified() {
        UserEntity user = buildUser(KycStatus.VERIFIED);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.VERIFIED);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));

        KycStatusResponse resp = service.getStatus("uid-001");

        assertThat(resp.kycStatus()).isEqualTo("VERIFIED");
        assertThat(resp.verificationStatus()).isEqualTo("VERIFIED");
    }

    // ── createSession ─────────────────────────────────────────────────────────

    @Test
    void createSession_userNotFound_throwsNotFoundException() {
        when(userRepository.findByFirebaseUid("uid-xxx")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createSession("uid-xxx"))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    @Test
    void createSession_alreadyVerified_throwsConflict() {
        UserEntity user = buildUser(KycStatus.VERIFIED);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));
        assertThatThrownBy(() -> service.createSession("uid-001"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("déjà vérifié");
    }

    @Test
    void createSession_stripeError_throwsServiceUnavailable() {
        UserEntity user = buildUser(KycStatus.PENDING);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            vsStatic.when(() -> VerificationSession.create(any(VerificationSessionCreateParams.class)))
                    .thenThrow(new RuntimeException("Stripe unavailable"));

            assertThatThrownBy(() -> service.createSession("uid-001"))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Impossible de créer la session");
        }
    }

    @Test
    void createSession_success_createsKycRecord() {
        UserEntity user = buildUser(KycStatus.PENDING);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession mockSession = mock(VerificationSession.class);
            when(mockSession.getId()).thenReturn("vs_test_new");
            when(mockSession.getUrl()).thenReturn("https://verify.stripe.com/start/vs_test_new");
            vsStatic.when(() -> VerificationSession.create(any(VerificationSessionCreateParams.class)))
                    .thenReturn(mockSession);

            KycSessionResponse resp = service.createSession("uid-001");

            assertThat(resp.sessionId()).isEqualTo("vs_test_new");
            assertThat(resp.status()).isEqualTo("PENDING");
            verify(kycRepository).save(any(KycVerificationEntity.class));
            verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_SESSION_CREATED"), any(), any());
        }
    }

    @Test
    void createSession_notStarted_transitionsToPendingAndCreatesKycRecord() {
        UserEntity user = buildUser(KycStatus.NOT_STARTED);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.empty());
        when(kycRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession mockSession = mock(VerificationSession.class);
            when(mockSession.getId()).thenReturn("vs_test_new");
            when(mockSession.getUrl()).thenReturn("https://verify.stripe.com/start/vs_test_new");
            vsStatic.when(() -> VerificationSession.create(any(VerificationSessionCreateParams.class)))
                    .thenReturn(mockSession);

            KycSessionResponse resp = service.createSession("uid-001");

            assertThat(resp.sessionId()).isEqualTo("vs_test_new");
            assertThat(user.getKycStatus()).isEqualTo(KycStatus.PENDING);
            verify(userRepository).save(user);
        }
    }

    // ── abandonSession ────────────────────────────────────────────────────────

    @Test
    void abandonSession_pending_resetsToNotStarted() {
        UserEntity user = buildUser(KycStatus.PENDING);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.abandonSession("uid-001");

        assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        verify(userRepository).save(user);
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_SESSION_ABANDONED"), any(), any());
    }

    @Test
    void abandonSession_notPending_doesNothing() {
        UserEntity user = buildUser(KycStatus.NOT_STARTED);
        when(userRepository.findByFirebaseUid("uid-001")).thenReturn(Optional.of(user));

        service.abandonSession("uid-001");

        verify(userRepository, never()).save(any());
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void abandonSession_userNotFound_throwsNotFoundException() {
        when(userRepository.findByFirebaseUid("unknown")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.abandonSession("unknown"))
                .isInstanceOf(YadonyNotFoundException.class);
    }

    // ── reconcilePendingVerifications / reconcileOne ────────────────────────────
    // Filet de sécurité si le webhook Stripe est perdu — retéléphone Stripe directement.

    @Test
    void reconcileOne_verified_updatesStatusAndPublishesEvent() {
        UserEntity user = buildUser(KycStatus.PENDING);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.PENDING);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession session = mock(VerificationSession.class);
            when(session.getStatus()).thenReturn("verified");
            vsStatic.when(() -> VerificationSession.retrieve(kyc.getStripeVerificationSessionId()))
                    .thenReturn(session);

            service.reconcileOne(kyc);

            assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
            assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
            verify(kycRepository).save(kyc);
            verify(userRepository).save(user);
            verify(eventPublisher).publishEvent(any(UserKycVerifiedEvent.class));
            verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_VERIFIED_RECONCILED"), any(), any());
        }
    }

    @Test
    void reconcileOne_requiresInput_setsRejectedWithReason() {
        UserEntity user = buildUser(KycStatus.PENDING);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.PENDING);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession session = mock(VerificationSession.class);
            when(session.getStatus()).thenReturn("requires_input");
            VerificationSession.LastError lastError = mock(VerificationSession.LastError.class);
            when(lastError.getCode()).thenReturn("document_expired");
            when(session.getLastError()).thenReturn(lastError);
            vsStatic.when(() -> VerificationSession.retrieve(kyc.getStripeVerificationSessionId()))
                    .thenReturn(session);

            service.reconcileOne(kyc);

            assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
            assertThat(kyc.getRejectionReason()).isEqualTo("document_expired");
            assertThat(user.getKycStatus()).isEqualTo(KycStatus.REJECTED);
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Test
    void reconcileOne_canceled_resetsToNotStarted() {
        UserEntity user = buildUser(KycStatus.PENDING);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.PENDING);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession session = mock(VerificationSession.class);
            when(session.getStatus()).thenReturn("canceled");
            vsStatic.when(() -> VerificationSession.retrieve(kyc.getStripeVerificationSessionId()))
                    .thenReturn(session);

            service.reconcileOne(kyc);

            assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
            assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        }
    }

    @Test
    void reconcileOne_stillProcessing_doesNothing() {
        UserEntity user = buildUser(KycStatus.PENDING);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.PENDING);

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession session = mock(VerificationSession.class);
            when(session.getStatus()).thenReturn("processing");
            vsStatic.when(() -> VerificationSession.retrieve(kyc.getStripeVerificationSessionId()))
                    .thenReturn(session);

            service.reconcileOne(kyc);

            verify(kycRepository, never()).save(any());
            verify(userRepository, never()).findById(any());
        }
    }

    @Test
    void reconcileOne_alreadyVerifiedLocally_skipsRaceWithWebhook() {
        UserEntity user = buildUser(KycStatus.VERIFIED);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.VERIFIED);
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession session = mock(VerificationSession.class);
            when(session.getStatus()).thenReturn("verified");
            vsStatic.when(() -> VerificationSession.retrieve(kyc.getStripeVerificationSessionId()))
                    .thenReturn(session);

            service.reconcileOne(kyc);

            verify(kycRepository, never()).save(any());
            verify(eventPublisher, never()).publishEvent(any());
        }
    }

    @Test
    void reconcileOne_stripeRetrieveFails_doesNotThrow() {
        KycVerificationEntity kyc = buildKyc(UUID.randomUUID(), KycVerificationStatus.PENDING);

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            vsStatic.when(() -> VerificationSession.retrieve(kyc.getStripeVerificationSessionId()))
                    .thenThrow(new RuntimeException("Stripe unavailable"));

            service.reconcileOne(kyc);

            verify(kycRepository, never()).save(any());
        }
    }

    @Test
    void reconcilePendingVerifications_queriesStaleSessionsAndReconcilesEach() {
        UserEntity user = buildUser(KycStatus.PENDING);
        KycVerificationEntity kyc = buildKyc(user.getId(), KycVerificationStatus.PENDING);
        when(kycRepository.findByStatusAndStripeVerificationSessionIdIsNotNullAndCreatedAtBefore(
                eq(KycVerificationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of(kyc));
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));

        try (MockedStatic<VerificationSession> vsStatic = mockStatic(VerificationSession.class)) {
            VerificationSession session = mock(VerificationSession.class);
            when(session.getStatus()).thenReturn("verified");
            vsStatic.when(() -> VerificationSession.retrieve(kyc.getStripeVerificationSessionId()))
                    .thenReturn(session);

            service.reconcilePendingVerifications();

            assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        }
    }

    @Test
    void reconcilePendingVerifications_noStaleSessions_doesNothing() {
        when(kycRepository.findByStatusAndStripeVerificationSessionIdIsNotNullAndCreatedAtBefore(
                eq(KycVerificationStatus.PENDING), any(LocalDateTime.class)))
                .thenReturn(List.of());

        service.reconcilePendingVerifications();

        verify(userRepository, never()).findById(any());
    }
}
