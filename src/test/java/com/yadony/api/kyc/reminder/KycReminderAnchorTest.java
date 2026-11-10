package com.yadony.api.kyc.reminder;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.stripe.AdminAlertService;
import com.yadony.api.config.PlatformSettingsService;
import com.yadony.api.kyc.KycAdminService;
import com.yadony.api.kyc.KycRefusedSessionRegistry;
import com.yadony.api.kyc.KycRepository;
import com.yadony.api.kyc.KycService;
import com.yadony.api.kyc.KycStatusTransitionService;
import com.yadony.api.kyc.KycVerificationEntity;
import com.yadony.api.kyc.KycVerificationStatus;
import com.yadony.api.kyc.provider.IdentityProviderResolver;
import com.yadony.api.kyc.provider.IdentityVerificationProvider;
import com.yadony.api.kyc.provider.ProviderSession;
import com.yadony.api.kyc.provider.VerificationProviderKind;
import com.yadony.api.notifications.NotificationDispatcher;
import com.yadony.api.common.i18n.TestMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ancre des relances de verification d'identite : posee, decalee ou effacee selon le parcours. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Relances KYC : ancre")
class KycReminderAnchorTest {

    @Mock KycRefusedSessionRegistry refusedSessions;
    @Mock KycRepository kycRepository;
    @Mock UserRepository userRepository;
    @Mock AuditService auditService;
    @Mock PlatformSettingsService settings;
    @Mock ApplicationEventPublisher eventPublisher;
    @Mock AdminAlertService adminAlert;
    @Mock NotificationDispatcher notificationDispatcher;

    IdentityVerificationProvider provider;
    KycService kycService;
    KycStatusTransitionService transitions;
    KycAdminService adminService;
    UserEntity user;
    KycVerificationEntity kyc;

    @BeforeEach
    void setUp() {
        provider = mock(IdentityVerificationProvider.class);
        lenient().when(provider.kind()).thenReturn(VerificationProviderKind.DIDIT);
        lenient().when(settings.kycDiditEnabled()).thenReturn(true);
        IdentityProviderResolver resolver = new IdentityProviderResolver(List.of(provider), settings);
        kycService = new KycService(kycRepository, userRepository, auditService, resolver, refusedSessions);
        transitions = new KycStatusTransitionService(kycRepository, userRepository, auditService,
                eventPublisher, adminAlert, refusedSessions);
        adminService = new KycAdminService(kycRepository, userRepository, auditService,
                notificationDispatcher, resolver);

        user = new UserEntity();
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        user.setFirebaseUid("uid-1");
        user.setKycStatus(KycStatus.PENDING);

        kyc = new KycVerificationEntity();
        ReflectionTestUtils.setField(kyc, "id", UUID.randomUUID());
        kyc.setUserId(user.getId());
        kyc.setProvider(VerificationProviderKind.DIDIT);
        kyc.setVerificationSessionId("sess_1");
        kyc.setStatus(KycVerificationStatus.PENDING);

        lenient().when(userRepository.findByFirebaseUid("uid-1")).thenReturn(Optional.of(user));
        lenient().when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        lenient().when(kycRepository.findByUserId(user.getId())).thenReturn(Optional.of(kyc));
        lenient().when(notificationDispatcher.messagesFor(any())).thenReturn(TestMessages.fr());
    }

    private static void assertRecent(Instant actual) {
        assertThat(actual).isCloseTo(Instant.now(), within(Duration.ofSeconds(5)));
    }

    @Test
    void nouvelleSession_demarreUnCycle() {
        user.setKycStatus(KycStatus.NOT_STARTED);
        user.setKycReminderCount(3);
        user.setKycReminderLastAt(Instant.now().minus(Duration.ofDays(1)));
        when(provider.createSession(eq(user), any())).thenReturn(new ProviderSession("https://x", "sess_new"));

        kycService.createSession("uid-1");

        assertThat(user.getKycReminderCount()).isZero();
        assertRecent(user.getKycReminderAnchorAt());
        assertThat(user.getKycReminderLastAt()).isNull();
    }

    @Test
    void repriseDeSession_decaleAncreSansRemettreLeCompteur() {
        Instant ancienne = Instant.now().minus(Duration.ofDays(2));
        user.setKycReminderCount(1);
        user.setKycReminderAnchorAt(ancienne);
        when(provider.createSession(user, "sess_1")).thenReturn(new ProviderSession("https://x", "sess_1"));

        kycService.createSession("uid-1");

        assertThat(user.getKycReminderCount()).isEqualTo(1);
        assertRecent(user.getKycReminderAnchorAt());
        org.mockito.Mockito.verify(userRepository).save(user);
    }

    @Test
    void abandonApp_decaleAncre() {
        user.setKycReminderAnchorAt(Instant.now().minus(Duration.ofDays(2)));

        kycService.abandonSession("uid-1");

        assertRecent(user.getKycReminderAnchorAt());
    }

    @Test
    void abandonDidit_decaleAncre() {
        user.setKycReminderAnchorAt(Instant.now().minus(Duration.ofDays(2)));

        transitions.markRestartable(kyc, user, "sess_1", "KYC_ABANDONED");

        assertRecent(user.getKycReminderAnchorAt());
    }

    @Test
    void refusDidit_decaleAncre() {
        user.setKycReminderAnchorAt(Instant.now().minus(Duration.ofDays(2)));

        transitions.markRejected(kyc, user, "sess_1", "ID_EXPIRED", "document expire");

        assertRecent(user.getKycReminderAnchorAt());
    }

    @Test
    void refusIgnoreSurLigneVerifiee_neTouchePasAncre() {
        Instant ancienne = Instant.now().minus(Duration.ofDays(2));
        user.setKycReminderAnchorAt(ancienne);
        kyc.setStatus(KycVerificationStatus.VERIFIED);

        transitions.markRejected(kyc, user, "sess_1", "ID_EXPIRED", "document expire");

        assertThat(user.getKycReminderAnchorAt()).isEqualTo(ancienne);
    }

    @Test
    void resetAdmin_effaceAncre() {
        user.setKycReminderAnchorAt(Instant.now().minus(Duration.ofDays(2)));

        adminService.resetForUser(user.getId(), UUID.randomUUID(), "test");

        assertThat(user.getKycReminderAnchorAt()).isNull();
    }
}
