package com.yadony.api.auth;

import com.yadony.api.auth.dto.PublishCommitmentsResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Service réel, base réelle : le bean se construit dans le contexte Spring (deux
 * constructeurs) et la migration V318 porte bien les colonnes lues et écrites.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PublishCommitmentIT {

    @Autowired PublishCommitmentService service;
    @Autowired UserRepository userRepository;

    @Test
    void accepterPuisRelire() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-commitment-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(new HashSet<>(Set.of(Role.TRAVELER)));
        UUID id = userRepository.saveAndFlush(user).getId();

        assertThat(service.get(id).traveler().required()).isTrue();

        service.accept(id, PublishCommitmentRole.TRAVELER, PublishCommitmentService.CURRENT_VERSION);

        PublishCommitmentsResponse r = service.get(id);
        assertThat(r.traveler().required()).isFalse();
        assertThat(r.traveler().acceptedVersion()).isEqualTo(PublishCommitmentService.CURRENT_VERSION);
        assertThat(r.sender().required()).isTrue();
    }
}
