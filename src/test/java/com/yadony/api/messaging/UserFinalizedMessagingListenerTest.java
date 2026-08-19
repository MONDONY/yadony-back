package com.yadony.api.messaging;

import com.yadony.api.auth.FinalizationReason;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.auth.events.UserFinalizedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Correction annexe (défaut préexistant) : {@link FirestoreService#anonymizeUser} interroge
 * Firestore avec l'UID Firebase — jamais l'UUID PostgreSQL, qui ne matche aucun message puisque
 * les champs {@code senderId}/{@code travelerId} des documents Firestore contiennent l'UID
 * Firebase (cf. {@link ConversationService}).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserFinalizedMessagingListener")
class UserFinalizedMessagingListenerTest {

    @Mock private FirestoreService firestoreService;
    @Mock private UserRepository userRepository;

    @Test
    @DisplayName("anonymise Firestore avec l'UID Firebase de l'utilisateur, pas son UUID PostgreSQL")
    void onUserFinalized_anonymizesFirestoreWithFirebaseUid_notPostgresUuid() throws Exception {
        UUID userId = UUID.randomUUID();
        String firebaseUid = "fb-uid-anonymize-001";
        UserEntity user = new UserEntity();
        setField(user, "firebaseUid", firebaseUid);
        // AFTER_COMMIT : le compte est déjà soft-deleted en base à ce stade — la lecture
        // doit donc contourner le filtre @Where(deleted_at IS NULL) de UserEntity.
        when(userRepository.findByIdIncludingDeleted(userId)).thenReturn(Optional.of(user));

        var listener = new UserFinalizedMessagingListener(firestoreService, userRepository);
        listener.onUserFinalized(new UserFinalizedEvent(userId, FinalizationReason.HARD_IMMEDIATE));

        verify(firestoreService).anonymizeUser(firebaseUid);
        verify(firestoreService, never()).anonymizeUser(userId.toString());
    }

    @Test
    @DisplayName("utilisateur introuvable (y compris soft-deleted) → aucune anonymisation, pas d'exception")
    void onUserFinalized_userNotFound_doesNotThrow_andSkipsAnonymize() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findByIdIncludingDeleted(userId)).thenReturn(Optional.empty());

        var listener = new UserFinalizedMessagingListener(firestoreService, userRepository);
        listener.onUserFinalized(new UserFinalizedEvent(userId, FinalizationReason.SOFT_GRACE_EXPIRED));

        verify(firestoreService, never()).anonymizeUser(any());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
