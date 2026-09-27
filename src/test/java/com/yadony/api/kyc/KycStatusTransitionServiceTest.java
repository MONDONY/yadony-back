package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.kyc.events.UserKycActionRequiredEvent;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class KycStatusTransitionServiceTest {

    @org.mockito.Mock KycRefusedSessionRegistry refusedSessions;
    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AdminAlertService adminAlert;

    private KycStatusTransitionService service;
    private KycVerificationEntity kyc;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new KycStatusTransitionService(kycRepository, userRepository, auditService,
                eventPublisher, adminAlert, refusedSessions);

        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setKycStatus(KycStatus.PENDING);

        kyc = new KycVerificationEntity();
        ReflectionTestUtils.setField(kyc, "id", UUID.randomUUID());
        kyc.setUserId(user.getId());
        kyc.setStatus(KycVerificationStatus.PENDING);
    }

    @Test
    void markVerified_setsBothStatuses_auditsAndPublishes() {
        service.markVerified(kyc, user, "sess_1");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        verify(kycRepository).save(kyc);
        verify(userRepository).save(user);
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_VERIFIED"), any(), anyMap());
        verify(eventPublisher).publishEvent(any(UserKycVerifiedEvent.class));
    }

    @Test
    void markVerified_isIdempotent_whenAlreadyVerified() {
        kyc.setStatus(KycVerificationStatus.VERIFIED);

        service.markVerified(kyc, user, "sess_1");

        verifyNoInteractions(eventPublisher);
        verify(kycRepository, never()).save(any());
    }

    @Test
    void markRejected_setsBothStatuses_alertsAndAsksForAction() {
        service.markRejected(kyc, user, "sess_1", "document_unreadable", "Pièce illisible");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
        assertThat(kyc.getRejectionCode()).isEqualTo("document_unreadable");
        assertThat(kyc.getRejectionReason()).isEqualTo("Pièce illisible");
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_REJECTED"), any(), anyMap());
        verify(adminAlert).raise(eq("KYC_IDENTITY_REJECTED"), anyString(), anyMap());

        ArgumentCaptor<UserKycActionRequiredEvent> event =
                ArgumentCaptor.forClass(UserKycActionRequiredEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().userId()).isEqualTo(user.getId());
    }

    @Test
    void markRejected_neverDowngradesAVerifiedRow() {
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        user.setKycStatus(KycStatus.VERIFIED);

        service.markRejected(kyc, user, "sess_1", "document_unreadable", "Pièce illisible");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        verifyNoInteractions(adminAlert, eventPublisher);
    }

    @Test
    void markInReview_keepsThingsPending_withoutEventNorAlert() {
        user.setKycStatus(KycStatus.NOT_STARTED);

        service.markInReview(kyc, user, "sess_1");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.PENDING);
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_IN_REVIEW"), any(), anyMap());
        verifyNoInteractions(eventPublisher, adminAlert);
    }

    @Test
    void markInReview_neverDowngradesAVerifiedRow() {
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        user.setKycStatus(KycStatus.VERIFIED);

        service.markInReview(kyc, user, "sess_1");

        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        verify(kycRepository, never()).save(any());
    }

    @Test
    void markRestartable_leavesTheUserAbleToStartAgain_withoutAdminAlert() {
        service.markRestartable(kyc, user, "sess_1", "KYC_ABANDONED");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.NOT_STARTED);
        verify(auditService).log(eq("kyc_verification"), any(), eq("KYC_ABANDONED"), any(), anyMap());
        verifyNoInteractions(adminAlert, eventPublisher);
    }

    @Test
    void markRestartable_neverDowngradesAVerifiedRow() {
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        user.setKycStatus(KycStatus.VERIFIED);

        service.markRestartable(kyc, user, "sess_1", "KYC_EXPIRED");

        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        verify(kycRepository, never()).save(any());
    }

    // ── Décisions d'administration ────────────────────────────────────────────

    private static final UUID ADMIN_ID = UUID.randomUUID();

    @Test
    void markInReview_datesTheSubmission_once() {
        service.markInReview(kyc, user, "sess_1");
        java.time.LocalDateTime first = kyc.getSubmittedAt();
        assertThat(first).isNotNull();

        service.markInReview(kyc, user, "sess_1");
        assertThat(kyc.getSubmittedAt()).as("un webhook rejoué ne rajeunit pas la demande").isEqualTo(first);
    }

    @Test
    void markRestartable_clearsTheSubmission() {
        kyc.setSubmittedAt(java.time.LocalDateTime.now());

        service.markRestartable(kyc, user, "sess_1", "KYC_EXPIRED");

        assertThat(kyc.getSubmittedAt()).isNull();
    }

    @Test
    void approveByAdmin_verifiesBothStatuses_recordsTheDecision_andPublishesTheSameEvent() {
        kyc.setVerificationSessionId("sess_1");
        kyc.setRejectionCode("document_expired");
        kyc.setRejectionReason("document_expired");

        service.approveByAdmin(kyc, user, ADMIN_ID, "Pièce contrôlée à la main, conforme");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.VERIFIED);
        assertThat(kyc.getDecisionKind()).isEqualTo(KycDecisionKind.APPROVED);
        assertThat(kyc.getDecidedByAdminId()).isEqualTo(ADMIN_ID);
        assertThat(kyc.getDecidedAt()).isNotNull();
        assertThat(kyc.getDecisionReason()).isEqualTo("Pièce contrôlée à la main, conforme");
        assertThat(kyc.getRejectionCode()).isNull();
        assertThat(kyc.getRejectionReason()).isNull();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> payload = ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditService).log(eq("kyc_verification"), eq(kyc.getId()), eq("KYC_VERIFIED_BY_ADMIN"),
                eq(ADMIN_ID), payload.capture());
        assertThat(payload.getValue()).containsEntry("previousStatus", "PENDING")
                .containsEntry("reason", "Pièce contrôlée à la main, conforme")
                .containsEntry("sessionId", "sess_1");
        verify(eventPublisher).publishEvent(any(UserKycVerifiedEvent.class));
        verifyNoInteractions(adminAlert);
    }

    @Test
    void rejectByAdmin_rejects_withoutAdminAlert_andNeverExposesTheInternalReason() {
        kyc.setVerificationSessionId("sess_1");

        service.rejectByAdmin(kyc, user, ADMIN_ID, "selfie_face_mismatch", "Le selfie ne ressemble pas");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        assertThat(kyc.getRejectionCode()).isEqualTo("selfie_face_mismatch");
        assertThat(kyc.getRejectionReason()).as("relu par l'app : jamais le motif interne")
                .isEqualTo("selfie_face_mismatch");
        assertThat(kyc.getDecisionKind()).isEqualTo(KycDecisionKind.REJECTED);
        assertThat(kyc.getDecisionReason()).isEqualTo("Le selfie ne ressemble pas");
        verify(auditService).log(eq("kyc_verification"), eq(kyc.getId()), eq("KYC_REJECTED_BY_ADMIN"),
                eq(ADMIN_ID), anyMap());
        verifyNoInteractions(adminAlert);

        ArgumentCaptor<UserKycActionRequiredEvent> event =
                ArgumentCaptor.forClass(UserKycActionRequiredEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().reasonCode()).isEqualTo("selfie_face_mismatch");
    }

    @Test
    void revokeByAdmin_downgradesAVerifiedRow_andPublishesTheRevocation() {
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        user.setKycStatus(KycStatus.VERIFIED);

        service.revokeByAdmin(kyc, user, ADMIN_ID, "document_unverified_other",
                "Pièce signalée comme volée par la police");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        assertThat(kyc.getDecisionKind()).isEqualTo(KycDecisionKind.REVOKED);
        assertThat(kyc.getRejectionCode()).isEqualTo("document_unverified_other");
        verify(auditService).log(eq("kyc_verification"), eq(kyc.getId()), eq("KYC_REVOKED_BY_ADMIN"),
                eq(ADMIN_ID), anyMap());
        verify(eventPublisher).publishEvent(any(com.yadony.api.kyc.events.UserKycRevokedEvent.class));
        verifyNoInteractions(adminAlert);
    }

    @Test
    void providerApproval_afterAnAdminRevocation_isIgnored() {
        kyc.setStatus(KycVerificationStatus.REJECTED);
        user.setKycStatus(KycStatus.REJECTED);
        kyc.setDecisionKind(KycDecisionKind.REVOKED);

        service.markVerified(kyc, user, "sess_1");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void providerTransitions_afterAnAdminRejection_areIgnored() {
        kyc.setStatus(KycVerificationStatus.REJECTED);
        user.setKycStatus(KycStatus.REJECTED);
        kyc.setDecisionKind(KycDecisionKind.REJECTED);

        service.markInReview(kyc, user, "sess_1");
        service.markRestartable(kyc, user, "sess_1", "KYC_EXPIRED");
        service.markRejected(kyc, user, "sess_1", "x", "y");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.REJECTED);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.REJECTED);
        verify(kycRepository, never()).save(any());
        verifyNoInteractions(adminAlert, eventPublisher);
    }

    @Test
    void clearDecision_resetsEveryDecisionField() {
        kyc.setDecisionKind(KycDecisionKind.REJECTED);
        kyc.setDecidedByAdminId(ADMIN_ID);
        kyc.setDecidedAt(java.time.LocalDateTime.now());
        kyc.setDecisionReason("motif");
        kyc.setSubmittedAt(java.time.LocalDateTime.now());

        kyc.clearDecision();

        assertThat(kyc.getDecisionKind()).isNull();
        assertThat(kyc.getDecidedByAdminId()).isNull();
        assertThat(kyc.getDecidedAt()).isNull();
        assertThat(kyc.getDecisionReason()).isNull();
        assertThat(kyc.getSubmittedAt()).isNull();
    }
}
