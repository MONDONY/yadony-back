package com.yadony.api.kyc;

import com.yadony.api.kyc.provider.VerificationProviderKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycRefusedSessionRegistryTest {

    @Mock KycRefusedSessionRepository repository;
    KycRefusedSessionRegistry registry;
    KycVerificationEntity kyc;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        registry = new KycRefusedSessionRegistry(repository);
        kyc = new KycVerificationEntity();
        ReflectionTestUtils.setField(kyc, "id", UUID.randomUUID());
        kyc.setUserId(UUID.randomUUID());
        kyc.setProvider(VerificationProviderKind.DIDIT);
        kyc.setVerificationSessionId("sess_refused");
    }

    @Test
    void remember_memoriseLaSessionRefusee() {
        when(repository.existsBySessionId("sess_refused")).thenReturn(false);

        registry.remember(kyc, KycDecisionKind.REVOKED, adminId);

        ArgumentCaptor<KycRefusedSessionEntity> saved = ArgumentCaptor.forClass(KycRefusedSessionEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getSessionId()).isEqualTo("sess_refused");
        assertThat(saved.getValue().getUserId()).isEqualTo(kyc.getUserId());
        assertThat(saved.getValue().getProvider()).isEqualTo(VerificationProviderKind.DIDIT);
        assertThat(saved.getValue().getDecisionKind()).isEqualTo(KycDecisionKind.REVOKED);
        assertThat(saved.getValue().getDecidedByAdminId()).isEqualTo(adminId);
        assertThat(saved.getValue().getDecidedAt()).isNotNull();
    }

    @Test
    void remember_estIdempotent_etIgnoreUneLigneSansSession() {
        when(repository.existsBySessionId("sess_refused")).thenReturn(true);
        registry.remember(kyc, KycDecisionKind.REJECTED, adminId);
        verify(repository, never()).save(any());

        kyc.setVerificationSessionId(null);
        registry.remember(kyc, KycDecisionKind.REJECTED, adminId);
        verify(repository, never()).save(any());
    }

    @Test
    void isRefused_consulteLeRegistre() {
        when(repository.existsBySessionId("sess_refused")).thenReturn(true);
        assertThat(registry.isRefused("sess_refused")).isTrue();
    }

    @Test
    void isRefused_sansIdentifiant_estFaux() {
        assertThat(registry.isRefused(null)).isFalse();
        assertThat(registry.isRefused(" ")).isFalse();
        verifyNoInteractions(repository);
    }
}
