package com.yadony.api.kyc;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.LocalizedResponseStatusException;
import com.yadony.api.kyc.provider.IdentityProviderResolver;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycAbandonReasonTest {

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock IdentityProviderResolver providers;
    @Mock KycRefusedSessionRegistry refusedSessions;

    private KycService service;
    private UserEntity user;
    private KycVerificationEntity kyc;

    @BeforeEach
    void setUp() {
        service = new KycService(kycRepository, userRepository, auditService, providers, refusedSessions);
        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        kyc = new KycVerificationEntity();
        ReflectionTestUtils.setField(kyc, "id", UUID.randomUUID());
        kyc.setUserId(user.getId());
        kyc.setProvider(VerificationProviderKind.DIDIT);
        kyc.setVerificationSessionId("sess_1");
        when(userRepository.findByFirebaseUid("uid")).thenReturn(Optional.of(user));
    }

    @Test
    void enregistre_la_raison_dans_l_audit_sans_donnee_d_identite() {
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));

        service.recordAbandonReason("uid", "PRIVACY");

        verify(auditService).log(eq("kyc_verification"), eq(kyc.getId()), eq("KYC_ABANDON_REASON"),
                eq(user.getId()), eq(Map.of("reason", "PRIVACY", "sessionId", "sess_1", "provider", "DIDIT")));
    }

    @Test
    void refuse_une_raison_hors_liste() {
        assertThatThrownBy(() -> service.recordAbandonReason("uid", "SELFIE_IS_UGLY"))
                .isInstanceOf(LocalizedResponseStatusException.class);
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void refuse_sans_session_ouverte() {
        kyc.setVerificationSessionId(null);
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));

        assertThatThrownBy(() -> service.recordAbandonReason("uid", "LATER"))
                .isInstanceOf(LocalizedResponseStatusException.class);
        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void toutes_les_raisons_proposees_sont_acceptees() {
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));
        for (String reason : KycService.ABANDON_REASONS) {
            service.recordAbandonReason("uid", reason);
        }
        assertThat(KycService.ABANDON_REASONS)
                .containsExactlyInAnyOrder("NO_DOCUMENT", "PRIVACY", "TOO_LONG", "TECHNICAL", "LATER", "OTHER");
    }
}
