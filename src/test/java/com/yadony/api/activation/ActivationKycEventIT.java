package com.yadony.api.activation;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.kyc.events.UserKycVerifiedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le webhook Didit publie UserKycVerifiedEvent HORS transaction : l'écouteur doit
 * quand même horodater kyc_verified_at (sinon aucune relance pour les nouveaux comptes).
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivationKycEventIT {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired UserRepository userRepository;

    @Test
    void eventPublishedOutsideTransaction_stampsKycVerifiedAt() {
        UserEntity user = userRepository.saveAndFlush(
                ActivationTestUsers.newUser("uid-kyc-event-" + UUID.randomUUID()));

        publisher.publishEvent(new UserKycVerifiedEvent(user.getId()));

        assertThat(userRepository.findById(user.getId()).orElseThrow().getKycVerifiedAt()).isNotNull();
    }
}
