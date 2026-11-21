package com.yadony.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** La migration V391 porte les colonnes et le contexte construit {@link DeclaredPhoneHasher}. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DeclaredPhoneColumnsIT {

    @Autowired UserRepository userRepository;
    @Autowired DeclaredPhoneHasher hasher;

    @Test
    void findAllByDeclaredPhoneHash_returnsAccountsDeclaringTheNumber() {
        String hash = hasher.hash("+2250707070707");
        UUID admin = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now().withNano(0);

        UserEntity user = new UserEntity();
        user.setFirebaseUid("uid-declared-" + UUID.randomUUID());
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.PENDING);
        user.setRoles(new HashSet<>(Set.of(Role.TRAVELER)));
        user.setDeclaredPhoneHash(hash);
        user.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        user.setDeclaredPhoneVerifiedAt(now);
        user.setDeclaredPhoneVerifiedBy(admin);
        userRepository.saveAndFlush(user);

        List<UserEntity> found = userRepository.findAllByDeclaredPhoneHash(hash);

        assertThat(found).hasSize(1);
        UserEntity u = found.get(0);
        assertThat(u.getDeclaredPhoneHash()).isEqualTo(hash);
        assertThat(u.getDeclaredPhoneLevel()).isEqualTo(DeclaredPhoneLevel.YADONY_VERIFIED);
        assertThat(u.getDeclaredPhoneVerifiedAt()).isEqualTo(now);
        assertThat(u.getDeclaredPhoneVerifiedBy()).isEqualTo(admin);
        assertThat(userRepository.findAllByDeclaredPhoneHash(hasher.hash("+2250101010101"))).isEmpty();
    }
}
