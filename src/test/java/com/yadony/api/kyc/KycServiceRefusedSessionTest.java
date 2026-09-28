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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code createSession} ne resert jamais un identifiant de session refuse par un administrateur. */
@ExtendWith(MockitoExtension.class)
class KycServiceRefusedSessionTest {

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock PlatformSettingsService settings;
    @Mock KycRefusedSessionRegistry refusedSessions;

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
        user.setFirebaseUid("uid-r");
        user.setKycStatus(KycStatus.NOT_STARTED);
        when(userRepository.findByFirebaseUid("uid-r")).thenReturn(Optional.of(user));
        kyc = new KycVerificationEntity();
        ReflectionTestUtils.setField(kyc, "id", UUID.randomUUID());
        kyc.setUserId(user.getId());
        kyc.setProvider(VerificationProviderKind.DIDIT);
        kyc.setVerificationSessionId("sess_refused");
        kyc.setStatus(KycVerificationStatus.PENDING);
        when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));
    }

    @Test
    void sessionRefuseeResservie_forceUneNouvelleSession() {
        when(refusedSessions.isRefused("sess_refused")).thenReturn(true);
        when(provider.createSession(eq(user), isNull())).thenReturn(new ProviderSession("https://old", "sess_refused"));
        when(provider.createFreshSession(user)).thenReturn(new ProviderSession("https://new", "sess_fresh"));

        var response = service.createSession("uid-r");

        // L'identifiant refuse n'est meme pas propose a la reprise.
        verify(provider, never()).createSession(eq(user), eq("sess_refused"));
        assertThat(response.sessionId()).isEqualTo("sess_fresh");
        assertThat(kyc.getVerificationSessionId()).isEqualTo("sess_fresh");
        verify(kycRepository).save(kyc);
        verify(auditService).log(eq("kyc_verification"), eq(kyc.getId()), eq("KYC_REFUSED_SESSION_NOT_REUSED"),
                eq(user.getId()), any());
    }

    @Test
    void fournisseurResertEncoreLaSessionRefusee_503() {
        when(refusedSessions.isRefused("sess_refused")).thenReturn(true);
        when(provider.createSession(eq(user), isNull())).thenReturn(new ProviderSession("https://old", "sess_refused"));
        when(provider.createFreshSession(user)).thenReturn(new ProviderSession("https://old", "sess_refused"));

        assertThatThrownBy(() -> service.createSession("uid-r"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(kycRepository, never()).save(any());
    }

    @Test
    void sessionNonRefusee_repriseCommeAvant() {
        when(refusedSessions.isRefused(any())).thenReturn(false);
        when(provider.createSession(user, "sess_refused")).thenReturn(new ProviderSession("https://x", "sess_refused"));

        var response = service.createSession("uid-r");

        assertThat(response.sessionId()).isEqualTo("sess_refused");
        verify(provider, never()).createFreshSession(any());
    }
}
