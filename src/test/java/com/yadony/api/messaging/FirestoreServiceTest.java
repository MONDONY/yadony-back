package com.yadony.api.messaging;

import com.google.api.core.ApiFuture;
import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FirestoreServiceTest {

    @Test
    void createConversation_doesNotThrow_whenFirestoreIsNull() {
        var service = new FirestoreService(null);
        service.createConversation("conv_test", Map.of("key", "val"));
    }

    @Test
    void addSystemMessage_doesNotThrow_whenFirestoreIsNull() {
        var service = new FirestoreService(null);
        service.addSystemMessage("conv_test", "Hello system");
    }

    @Test
    void updateLastMessage_doesNotThrow_whenFirestoreIsNull() {
        var service = new FirestoreService(null);
        service.updateLastMessage("conv_test", "preview", "2026-04-29T10:00:00Z");
    }

    @Test
    void softDeleteMessage_doesNotThrow_whenFirestoreIsNull() {
        var service = new FirestoreService(null);
        service.softDeleteMessage("conv_test", "msg_001");
    }

    @Test
    void setMessagingMute_doesNotThrow_whenFirestoreIsNull() {
        var service = new FirestoreService(null);
        service.setMessagingMute("fb-uid-123", Instant.now().plusSeconds(3600));
    }

    @Test
    void clearMessagingMute_doesNotThrow_whenFirestoreIsNull() {
        var service = new FirestoreService(null);
        service.clearMessagingMute("fb-uid-123");
    }

    // ── Lot B : coupure de messagerie — moderation/{firebaseUid} ────────────────

    @Test
    void setMessagingMute_writesModerationDoc_keyedOnFirebaseUid_notPostgresUuid() {
        Firestore firestore = mock(Firestore.class);
        CollectionReference collection = mock(CollectionReference.class);
        DocumentReference document = mock(DocumentReference.class);
        when(firestore.collection("moderation")).thenReturn(collection);
        // UID Firebase réaliste (pas un UUID) : un document keyé sur l'UUID Postgres
        // ne matcherait jamais rien pour la règle Firestore, qui ne voit que
        // request.auth.uid.
        String firebaseUid = "fbUidNotAUuid123456789";
        when(collection.document(firebaseUid)).thenReturn(document);
        ApiFuture<WriteResult> future = ApiFutures.immediateFuture(mock(WriteResult.class));
        when(document.set(anyMap())).thenReturn(future);

        var service = new FirestoreService(firestore);
        service.setMessagingMute(firebaseUid, Instant.now().plusSeconds(3600));

        verify(collection).document(firebaseUid);
        verify(document).set(anyMap());
    }

    /**
     * Point d'attention 2 du brief Task 4 : la règle Firestore est fail-open sur le
     * type — si {@code messagingMutedUntil} n'est pas un Timestamp Firestore, le mute
     * ne s'applique pas du tout, silencieusement. Toutes les autres dates de ce fichier
     * (sentAt, deletedAt, lastMessageAt) sont écrites en chaîne ISO : cette assertion sur
     * le TYPE réel écrit est indispensable pour ne pas retomber dans ce piège.
     */
    @Test
    void setMessagingMute_writesMessagingMutedUntilAsFirestoreTimestamp_notIsoString() {
        Firestore firestore = mock(Firestore.class);
        CollectionReference collection = mock(CollectionReference.class);
        DocumentReference document = mock(DocumentReference.class);
        when(firestore.collection("moderation")).thenReturn(collection);
        when(collection.document("fb-uid-123")).thenReturn(document);
        ApiFuture<WriteResult> future = ApiFutures.immediateFuture(mock(WriteResult.class));
        when(document.set(anyMap())).thenReturn(future);

        var service = new FirestoreService(firestore);
        // Tronqué à la milliseconde : com.google.cloud.Timestamp passe par java.util.Date,
        // qui ne porte pas la précision nanoseconde — non pertinent pour une durée de mute
        // exprimée en heures, mais l'égalité exacte du test doit en tenir compte.
        Instant until = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        service.setMessagingMute("fb-uid-123", until);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(document).set(captor.capture());
        Object writtenValue = captor.getValue().get("messagingMutedUntil");

        assertThat(writtenValue)
                .as("messagingMutedUntil doit être un com.google.cloud.Timestamp Firestore,"
                        + " jamais une chaîne ISO — sinon la règle fail-open laisse le mute inopérant")
                .isInstanceOf(Timestamp.class)
                .isNotInstanceOf(String.class);
        assertThat(((Timestamp) writtenValue).toDate().toInstant()).isEqualTo(until);
    }

    @Test
    void clearMessagingMute_deletesModerationDoc_keyedOnFirebaseUid() {
        Firestore firestore = mock(Firestore.class);
        CollectionReference collection = mock(CollectionReference.class);
        DocumentReference document = mock(DocumentReference.class);
        when(firestore.collection("moderation")).thenReturn(collection);
        when(collection.document("fb-uid-123")).thenReturn(document);
        ApiFuture<WriteResult> future = ApiFutures.immediateFuture(mock(WriteResult.class));
        when(document.delete()).thenReturn(future);

        var service = new FirestoreService(firestore);
        service.clearMessagingMute("fb-uid-123");

        verify(collection).document("fb-uid-123");
        verify(document).delete();
    }
}
