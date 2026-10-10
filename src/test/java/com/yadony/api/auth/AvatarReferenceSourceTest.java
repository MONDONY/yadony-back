package com.yadony.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@Import(AvatarReferenceSource.class)
class AvatarReferenceSourceTest {

    @Autowired UserRepository users;
    @Autowired AvatarReferenceSource source;

    private UserEntity user(String avatar, boolean softDeleted) {
        UserEntity u = new UserEntity();
        u.setFirebaseUid("uid-" + UUID.randomUUID());
        u.setUsername("user-" + UUID.randomUUID().toString().substring(0, 12));
        u.setRoles(new HashSet<>(Set.of(Role.SENDER)));
        u.setAvatarUrl(avatar);
        if (softDeleted) u.softDelete();
        return users.saveAndFlush(u);
    }

    @Test
    void avatars_includingSoftDeletedAccounts_areReferenced() {
        user("users/a/1.jpg", false);
        user("users/b/grace.jpg", true);   // compte en délai de grâce : avatar gardé
        user("https://h/yadony-prod/users/c/legacy.jpg?X-Amz-Signature=1", false);
        user("https://lh3.googleusercontent.com/photo.jpg", false);
        user(null, false);

        assertThat(source.prefixes()).containsExactly("users/");
        assertThat(source.referencedKeys("users/"))
                .containsExactlyInAnyOrder("users/a/1.jpg", "users/b/grace.jpg", "users/c/legacy.jpg");
    }
}
