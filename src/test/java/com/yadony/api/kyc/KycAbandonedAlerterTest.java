package com.yadony.api.kyc;

import com.yadony.api.admin.AdminAlertEntity;
import com.yadony.api.admin.AdminAlertEscalator;
import com.yadony.api.admin.AdminAlertRepository;
import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KycAbandonedAlerterTest {

    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AdminAlertRepository alertRepository;
    @Mock AdminAlertEscalator alerts;

    private KycAbandonedAlerter alerter;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        alerter = new KycAbandonedAlerter(kycRepository, userRepository, alertRepository, alerts);
        ReflectionTestUtils.setField(alerter, "enabled", true);
        ReflectionTestUtils.setField(alerter, "minIdle", Duration.ofMinutes(30));
        ReflectionTestUtils.setField(alerter, "maxAge", Duration.ofDays(7));
        ReflectionTestUtils.setField(alerter, "batchSize", 50);
    }

    private KycVerificationEntity kyc(LocalDateTime updatedAt) {
        KycVerificationEntity k = new KycVerificationEntity();
        k.setUserId(userId);
        k.setVerificationSessionId("sess_1");
        k.setStatus(KycVerificationStatus.PENDING);
        ReflectionTestUtils.setField(k, "updatedAt", updatedAt);
        return k;
    }

    @Test
    void levee_une_alerte_pour_un_parcours_quitte() {
        when(kycRepository.findAbandonedBefore(any(), any(Pageable.class)))
                .thenReturn(List.of(kyc(LocalDateTime.now(ZoneOffset.UTC).minusHours(2))));
        when(alerts.raiseOnce(eq("KYC_ABANDONED_" + userId), eq("INFO"), anyString(), any())).thenReturn(true);

        assertThat(alerter.raiseAbandoned()).isEqualTo(1);
    }

    @Test
    void ignore_un_parcours_plus_vieux_que_max_age() {
        when(kycRepository.findAbandonedBefore(any(), any(Pageable.class)))
                .thenReturn(List.of(kyc(LocalDateTime.now(ZoneOffset.UTC).minusDays(30))));

        assertThat(alerter.raiseAbandoned()).isZero();
        verify(alerts, never()).raiseOnce(anyString(), anyString(), anyString(), any());
    }

    @Test
    void clot_l_alerte_quand_l_utilisateur_a_repris() {
        AdminAlertEntity open = new AdminAlertEntity();
        open.setType("KYC_ABANDONED_" + userId);
        when(alertRepository.findOpenReferencing(anyString(), anyInt())).thenReturn(List.of(open));
        KycVerificationEntity k = kyc(LocalDateTime.now(ZoneOffset.UTC));
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(k));
        UserEntity user = new UserEntity();
        user.setKycStatus(KycStatus.PENDING);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(alerts.resolveOpen("KYC_ABANDONED_" + userId)).thenReturn(1);

        assertThat(alerter.resolveRecovered()).isEqualTo(1);
    }

    @Test
    void garde_l_alerte_tant_que_le_parcours_reste_quitte() {
        AdminAlertEntity open = new AdminAlertEntity();
        open.setType("KYC_ABANDONED_" + userId);
        when(alertRepository.findOpenReferencing(anyString(), anyInt())).thenReturn(List.of(open));
        when(kycRepository.findByUserId(userId)).thenReturn(Optional.of(kyc(LocalDateTime.now(ZoneOffset.UTC))));
        UserEntity user = new UserEntity();
        user.setKycStatus(KycStatus.NOT_STARTED);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThat(alerter.resolveRecovered()).isZero();
        verify(alerts, never()).resolveOpen(anyString());
    }
}
