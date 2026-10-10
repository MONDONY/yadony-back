package com.yadony.api.auth;

import com.yadony.api.common.media.MediaKeyReferenceSource;
import com.yadony.api.common.media.MediaKeys;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Avatars : {@code users.avatar_url}, comptes supprimés logiquement compris (délai de grâce :
 * le compte peut être restauré, AccountFinalizationService efface l'avatar à la finalisation).
 */
@Component
public class AvatarReferenceSource implements MediaKeyReferenceSource {

    private final UserRepository users;

    public AvatarReferenceSource(UserRepository users) {
        this.users = users;
    }

    @Override
    public Set<String> prefixes() {
        return Set.of("users/");
    }

    @Override
    public Set<String> referencedKeys(String prefix) {
        return MediaKeys.keysUnder(prefix, users.findAvatarValuesMatchingIncludingDeleted(prefix));
    }
}
