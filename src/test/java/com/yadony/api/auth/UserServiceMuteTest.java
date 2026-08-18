package com.yadony.api.auth;

import com.yadony.api.common.AuditService;
import com.yadony.api.messaging.FirestoreService;
import com.yadony.api.notifications.NotificationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Lot B : coupure de messagerie — base de données, Firestore, audit, notification.
 *
 * <p>Point d'attention 1 (brief Task 4) : le document Firestore est keyé sur l'UID
 * Firebase de l'utilisateur, JAMAIS son UUID PostgreSQL — ces tests vérifient donc que
 * {@code firestoreService.setMessagingMute}/{@code clearMessagingMute} reçoivent
 * {@link #FIREBASE_UID}, pas {@link #USER_ID}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — coupure de messagerie (Lot B)")
class UserServiceMuteTest {

    @Mock private UserRepository userRepository;
    @Mock private AuditService auditService;
    @Mock private FirestoreService firestoreService;
    @Mock private NotificationDispatcher notificationDispatcher;

    @InjectMocks private UserService service;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String FIREBASE_UID = "uid-mute-001";

    private UserEntity user;

    @BeforeEach
    void setUp() throws Exception {
        user = new UserEntity();
        setId(user, USER_ID);
        setField(user, "firebaseUid", FIREBASE_UID);
        setField(user, "status", UserStatus.ACTIVE);

        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        // lenient : les tests "user introuvable" re-stubbent findById() sur Optional.empty()
        // et n'atteignent jamais save().
        lenient().when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("mute avec durée : fixe l'échéance et propage à Firestore avec l'UID Firebase")
    void muteMessaging_withDuration_setsDeadlineAndPropagatesToFirestore() {
        UserEntity saved = service.muteMessaging(USER_ID, 24, "harcèlement");

        assertThat(saved.getMessagingMutedUntil()).isAfter(Instant.now().plusSeconds(23 * 3600));
        verify(firestoreService).setMessagingMute(eq(FIREBASE_UID), any(Instant.class));
        verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_MESSAGING_MUTED"), any(), anyMap());
        verify(notificationDispatcher).notifyUser(eq(USER_ID), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("mute indéfini (durationHours null) : échéance à +100 ans")
    void muteMessaging_indefinite_setsFarFutureDeadline() {
        UserEntity saved = service.muteMessaging(USER_ID, null, "fraude");

        // Une échéance très lointaine matérialise l'indéfini et garde la règle
        // Firestore à une seule comparaison.
        assertThat(saved.getMessagingMutedUntil()).isAfter(Instant.now().plusSeconds(365L * 24 * 3600));
        verify(firestoreService).setMessagingMute(eq(FIREBASE_UID), any(Instant.class));
    }

    @Test
    @DisplayName("unmute : lève l'échéance et supprime le document Firestore")
    void unmuteMessaging_clearsDeadlineAndDeletesFirestoreDoc() throws Exception {
        setField(user, "messagingMutedUntil", Instant.now().plusSeconds(3600));

        UserEntity saved = service.unmuteMessaging(USER_ID);

        assertThat(saved.getMessagingMutedUntil()).isNull();
        verify(firestoreService).clearMessagingMute(FIREBASE_UID);
        verify(auditService).log(eq("USER"), eq(USER_ID), eq("USER_MESSAGING_UNMUTED"), any(), anyMap());
    }

    @Test
    @DisplayName("mute utilisateur introuvable → 404")
    void muteMessaging_userNotFound_throws404() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.muteMessaging(USER_ID, 24, "spam"))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class)
                .satisfies(e -> assertThat(((com.yadony.api.common.YadonyBusinessException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        verifyNoInteractions(firestoreService);
    }

    @Test
    @DisplayName("unmute utilisateur introuvable → 404")
    void unmuteMessaging_userNotFound_throws404() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unmuteMessaging(USER_ID))
                .isInstanceOf(com.yadony.api.common.YadonyBusinessException.class)
                .satisfies(e -> assertThat(((com.yadony.api.common.YadonyBusinessException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND));
        verifyNoInteractions(firestoreService);
    }

    private static void setId(UserEntity entity, UUID id) throws Exception {
        Field field = findField(entity.getClass(), "id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
