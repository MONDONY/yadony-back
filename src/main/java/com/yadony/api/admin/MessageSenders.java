package com.yadony.api.admin;

import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.messaging.SystemMessages;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Retrouve le compte derrière le {@code senderId} d'un message Firestore.
 *
 * <p>L'app et le back écrivent l'UID Firebase de l'expéditeur ({@code users.firebase_uid}),
 * pas l'identifiant du compte. Un {@code senderId} au format UUID reste accepté (ancien format
 * possible) ; {@code SYSTEM} désigne la plateforme et n'a pas de compte.
 */
final class MessageSenders {

    private MessageSenders() {}

    /** Identifiant de compte si le {@code senderId} est un UUID, sinon {@code null}. */
    static UUID asUserId(String senderId) {
        if (senderId == null || SystemMessages.isSystemSender(senderId)) return null;
        try {
            return UUID.fromString(senderId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** UID Firebase si le {@code senderId} n'est ni vide, ni {@code SYSTEM}, ni un UUID ; sinon {@code null}. */
    static String asFirebaseUid(String senderId) {
        if (senderId == null || senderId.isBlank() || SystemMessages.isSystemSender(senderId)) return null;
        return asUserId(senderId) == null ? senderId : null;
    }

    /**
     * Comptes des expéditeurs, indexés par {@code senderId} tel qu'écrit dans Firestore.
     * Au plus deux requêtes (par UUID, par UID Firebase), quel que soit le nombre de messages.
     * Un compte supprimé n'est pas relu ({@code @Where deleted_at IS NULL}) : il est absent.
     */
    static Map<String, UserEntity> resolve(UserRepository userRepo, Collection<String> senderIds) {
        Set<UUID> ids = senderIds.stream().map(MessageSenders::asUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> uids = senderIds.stream().map(MessageSenders::asFirebaseUid)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        Map<String, UserEntity> bySenderId = new HashMap<>();
        if (!ids.isEmpty()) {
            for (UserEntity u : userRepo.findAllById(ids)) {
                if (u.getId() != null) bySenderId.putIfAbsent(u.getId().toString(), u);
            }
        }
        if (!uids.isEmpty()) {
            for (UserEntity u : userRepo.findAllByFirebaseUidIn(uids)) {
                if (u.getFirebaseUid() != null) bySenderId.putIfAbsent(u.getFirebaseUid(), u);
            }
        }
        return bySenderId;
    }
}
