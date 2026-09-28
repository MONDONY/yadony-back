package com.yadony.api.kyc;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.kyc.provider.IdentityProviderResolver;
import com.yadony.api.kyc.provider.IdentityVerificationProvider;
import com.yadony.api.kyc.provider.ProviderSession;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Nouvelle session utilisateur face à une décision d'administration : la décision et le
 * passage en revue sont effacés, et une ligne figée par un refus admin n'est jamais reprise
 * telle quelle.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KycService.createSession — décisions d'administration")
class KycServiceSessionDecisionTest {

    @org.mockito.Mock KycRefusedSessionRegistry refusedSessions;
    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock PlatformSettingsService settings;

    IdentityVerificationProvider provider;
    KycService service;
    UserEntity user;
    KycVerificationEntity kyc;

    @BeforeEach
    void setUp() {
        provider = mock(IdentityVerificationProvider.class);
        lenient().when(provider.kind()).thenReturn(VerificationProviderKind.DIDIT);
        when(settings.kycDiditEnabled()).thenReturn(true);
        service = new KycService(kycRepository, userRepository, auditService,
                new IdentityProviderResolver(List.of(provider), settings), refusedSessions);

        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setFirebaseUid("uid-1");
        user.setKycStatus(KycStatus.REJECTED);
        when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));

        kyc = new KycVerificationEntity();
        ReflectionTestUtils.setField(kyc, "id", UUID.randomUUID());
        kyc.setUserId(user.getId());
        kyc.setProvider(VerificationProviderKind.DIDIT);
        kyc.setVerificationSessionId("sess_old");
        kyc.setStatus(KycVerificationStatus.REJECTED);
        kyc.setDecisionKind(KycDecisionKind.REJECTED);
        kyc.setDecidedByAdminId(UUID.randomUUID());
        kyc.setDecidedAt(LocalDateTime.now());
        kyc.setDecisionReason("motif interne");
        kyc.setSubmittedAt(LocalDateTime.now());
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));
    }

    @Test
    void nouvelleSession_effaceLaDecisionEtLePassageEnRevue() {
        when(provider.createSession(eq(user), any())).thenReturn(new ProviderSession("https://x", "sess_new"));

        service.createSession("uid-1");

        assertThat(kyc.getVerificationSessionId()).isEqualTo("sess_new");
        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
        assertThat(kyc.getDecisionKind()).isNull();
        assertThat(kyc.getDecisionReason()).isNull();
        assertThat(kyc.getSubmittedAt()).isNull();
        verify(kycRepository).save(kyc);
    }

    @Test
    void ligneFigeeParUnRefusAdmin_nEstJamaisRepriseTelleQuelle() {
        // Même identifiant renvoyé (Didit resert une session inachevée) : la ligne est quand
        // même réécrite, sinon elle resterait figée et l'utilisateur bloqué.
        when(provider.createSession(eq(user), isNull())).thenReturn(new ProviderSession("https://x", "sess_old"));

        service.createSession("uid-1");

        verify(provider).createSession(eq(user), isNull());
        assertThat(kyc.getStatus()).isEqualTo(KycVerificationStatus.PENDING);
        assertThat(kyc.getDecisionKind()).isNull();
        verify(kycRepository).save(kyc);
    }

    @Test
    void sessionRepriseSansDecision_resteIntacte() {
        kyc.clearDecision();
        LocalDateTime enRevue = LocalDateTime.now().minusHours(3);
        kyc.setSubmittedAt(enRevue);
        kyc.setStatus(KycVerificationStatus.PENDING);
        user.setKycStatus(KycStatus.PENDING);
        when(provider.createSession(user, "sess_old")).thenReturn(new ProviderSession("https://x", "sess_old"));

        service.createSession("uid-1");

        assertThat(kyc.getSubmittedAt()).isEqualTo(enRevue);
        verify(kycRepository, never()).save(any());
    }
}
