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
                eventPublisher, adminAlert);

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
}
