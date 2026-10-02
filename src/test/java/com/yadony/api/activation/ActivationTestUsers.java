package com.yadony.api.activation;

import com.yadony.api.auth.KycStatus;
import com.yadony.api.auth.Role;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class ActivationTestUsers {
    private ActivationTestUsers() {}

    /** Utilisateur minimal valide pour H2 (même socle que TripsSummaryRepositoryIT). */
    static UserEntity newUser(String firebaseUid) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid(firebaseUid);
        user.setStatus(UserStatus.ACTIVE);
        user.setKycStatus(KycStatus.VERIFIED);
        Set<Role> roles = new HashSet<>();
        roles.add(Role.SENDER);
        user.setRoles(roles);
        return user;
    }

    /** Utilisateur hors base, avec un id, pour les tests unitaires. */
    static UserEntity withId() {
        UserEntity user = newUser("uid");
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
