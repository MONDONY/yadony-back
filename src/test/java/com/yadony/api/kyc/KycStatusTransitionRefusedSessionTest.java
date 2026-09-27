package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Didit resert une session inachevee du meme utilisateur : une session refusee ou revoquee par
 * un administrateur peut donc revenir apres une nouvelle tentative, la ligne repartie PENDING et
 * sa decision effacee. Aucun webhook positif portant cet identifiant ne doit plus jamais verifier
 * le compte.
 */
@ExtendWith(MockitoExtension.class)
class KycStatusTransitionRefusedSessionTest {

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AdminAlertService adminAlert;
    @Mock KycRefusedSessionRegistry refusedSessions;

    private KycStatusTransitionService service;
    private KycVerificationEntity kyc;
    private UserEntity user;
    private final UUID adminId = UUID.randomUUID();

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
        kyc.setVerificationSessionId("sess_x");
    }

    @Test
    void refusAdmin_memoriseLaSession() {
        service.rejectByAdmin(kyc, user, adminId, "document_unreadable", "photo floue");
        verify(refusedSessions).remember(kyc, KycDecisionKind.REJECTED, adminId);
    }

    @Test
    void revocationAdmin_memoriseLaSession() {
        kyc.setStatus(KycVerificationStatus.VERIFIED);
        service.revokeByAdmin(kyc, user, adminId, "document_fraud", "faux document");
        verify(refusedSessions).remember(kyc, KycDecisionKind.REVOKED, adminId);
    }

    @Test
    void webhookApprouve_surUneSessionRefusee_estIgnore() {
        when(refusedSessions.isRefused("sess_x")).thenReturn(true);

        service.markVerified(kyc, user, "sess_x");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
        assertThat(user.getKycStatus()).isEqualTo(KycStatus.PENDING);
        verify(eventPublisher, never()).publishEvent(any(UserKycVerifiedEvent.class));
        verify(kycRepository, never()).save(any());
        verify(auditService).log("kyc_verification", kyc.getId(), "KYC_WEBHOOK_IGNORED_REFUSED_SESSION",
                user.getId(), Map.of("sessionId", "sess_x", "event", "KYC_VERIFIED"));
    }

    @Test
    void webhookEnRevue_surUneSessionRefusee_estIgnore() {
        when(refusedSessions.isRefused("sess_x")).thenReturn(true);

        service.markInReview(kyc, user, "sess_x");

        assertThat(kyc.getSubmittedAt()).isNull();
        verify(kycRepository, never()).save(any());
        verify(auditService).log("kyc_verification", kyc.getId(), "KYC_WEBHOOK_IGNORED_REFUSED_SESSION",
                user.getId(), Map.of("sessionId", "sess_x", "event", "KYC_IN_REVIEW"));
    }

    @Test
    void webhookApprouve_surUneSessionNeuve_verifie() {
        when(refusedSessions.isRefused("sess_new")).thenReturn(false);

        service.markVerified(kyc, user, "sess_new");

        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.VERIFIED);
        verify(eventPublisher).publishEvent(any(UserKycVerifiedEvent.class));
    }
}
