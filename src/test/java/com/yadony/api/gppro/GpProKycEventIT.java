package com.yadony.api.gppro;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.UserStatus;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FLUTTER-RC : le webhook Didit publie UserKycVerifiedEvent HORS transaction. La demande GP Pro
 * en attente du KYC doit quand même partir en revue (avant, l'écouteur était ignoré).
 */
@SpringBootTest
@ActiveProfiles("test")
class GpProKycEventIT {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserRepository userRepository;
    @Autowired GpProApplicationRepository applications;

    @Test
    void eventPublishedOutsideTransaction_releasesApplicationAwaitingKyc() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-gp-pro-kyc-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.TRAVELER);
        user.setRoles(roles);
        UUID userId = userRepository.saveAndFlush(user).getId();

        GpProApplicationEntity app = new GpProApplicationEntity();
        app.setUserId(userId);
        app.setStatus(GpProApplicationStatus.AWAITING_KYC);
        app.setSource(GpProApplicationSource.SIGNUP);
        app.setTravelFrequency(GpProTravelFrequency.MONTHLY);
        UUID appId = applications.saveAndFlush(app).getId();

        publisher.publishEvent(new UserKycVerifiedEvent(userId));

        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(applications.findById(appId).orElseThrow().getStatus())
                        .isEqualTo(GpProApplicationStatus.PENDING));
    }
}
