package com.yadony.api.messaging;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link FirestoreService#listMessagesAround} : lecture bornée autour d'un message signalé. */
class FirestoreServiceAroundTest {

    private static final String SENT_AT = "2026-10-26T10:42:00Z";

    private final Firestore firestore = mock(Firestore.class);
    private final CollectionReference messages = mock(CollectionReference.class);
    private final DocumentReference anchorRef = mock(DocumentReference.class);

    private void wire() {
        CollectionReference conversations = mock(CollectionReference.class);
        DocumentReference conversation = mock(DocumentReference.class);
        when(firestore.collection("conversations")).thenReturn(conversations);
        when(conversations.document("conv_1")).thenReturn(conversation);
        when(conversation.collection("messages")).thenReturn(messages);
        when(messages.document("msg_2")).thenReturn(anchorRef);
    }

    private DocumentSnapshot anchor(boolean exists, String sentAt) {
        DocumentSnapshot snap = mock(DocumentSnapshot.class);
        when(snap.exists()).thenReturn(exists);
        if (exists) {
            when(snap.getString("sentAt")).thenReturn(sentAt);
            when(snap.getId()).thenReturn("msg_2");
            when(snap.getData()).thenReturn(Map.of("body", "signalé", "senderId", "uid-2", "sentAt", SENT_AT));
        }
        when(anchorRef.get()).thenReturn(ApiFutures.immediateFuture(snap));
        return snap;
    }

    private static QueryDocumentSnapshot doc(String id, String body) {
        QueryDocumentSnapshot d = mock(QueryDocumentSnapshot.class);
        when(d.getId()).thenReturn(id);
        when(d.getData()).thenReturn(Map.of("body", body));
        return d;
    }

    private static Query returning(List<QueryDocumentSnapshot> docs) {
        Query limited = mock(Query.class);
        QuerySnapshot snapshot = mock(QuerySnapshot.class);
        when(snapshot.getDocuments()).thenReturn(docs);
        when(limited.get()).thenReturn(ApiFutures.immediateFuture(snapshot));
        Query ordered = mock(Query.class);
        when(ordered.limit(org.mockito.ArgumentMatchers.anyInt())).thenReturn(limited);
        Query filtered = mock(Query.class);
        when(filtered.orderBy(org.mockito.ArgumentMatchers.eq("sentAt"))).thenReturn(ordered);
        when(filtered.orderBy(org.mockito.ArgumentMatchers.eq("sentAt"),
                org.mockito.ArgumentMatchers.eq(Query.Direction.DESCENDING))).thenReturn(ordered);
        return filtered;
    }

    @Test
    void firestoreDesactive_vide() {
        assertThat(new FirestoreService(null).listMessagesAround("conv_1", "msg_2", 2, 2)).isEmpty();
    }

    @Test
    void messageInconnu_vide_sansLireLesVoisins() {
        wire();
        anchor(false, null);

        assertThat(new FirestoreService(firestore).listMessagesAround("conv_1", "msg_2", 2, 2)).isEmpty();
        verify(messages, never()).whereLessThan(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rendLesVoisinsDuPlusAncienAuPlusRecent_leSignaleAuMilieu() {
        wire();
        anchor(true, SENT_AT);
        // Plus anciens : Firestore les rend du plus récent au plus ancien, le service les remet à l'endroit.
        // Les documents sont créés avant tout `when` : un mock ne se crée pas au milieu d'un stubbing.
        var olderDocs = List.of(doc("msg_1", "juste avant"), doc("msg_0", "avant"));
        var newerDocs = List.of(doc("msg_3", "après"), doc("msg_4", "encore après"));
        Query older = returning(olderDocs);
        Query newer = returning(newerDocs);
        when(messages.whereLessThan("sentAt", SENT_AT)).thenReturn(older);
        when(messages.whereGreaterThan("sentAt", SENT_AT)).thenReturn(newer);

        List<Map<String, Object>> result = new FirestoreService(firestore).listMessagesAround("conv_1", "msg_2", 2, 2);

        assertThat(result).extracting(m -> m.get("id")).containsExactly("msg_0", "msg_1", "msg_2", "msg_3", "msg_4");
        assertThat(result.get(2)).containsEntry("body", "signalé");
    }

    @Test
    void sansBorneAvantNiApres_neRendQueLeMessage() {
        wire();
        anchor(true, SENT_AT);

        List<Map<String, Object>> result = new FirestoreService(firestore).listMessagesAround("conv_1", "msg_2", 0, 0);

        assertThat(result).extracting(m -> m.get("id")).containsExactly("msg_2");
        verify(messages, never()).whereLessThan(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        verify(messages, never()).whereGreaterThan(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void messageSansDate_rendLuiSeul() {
        wire();
        anchor(true, null);

        List<Map<String, Object>> result = new FirestoreService(firestore).listMessagesAround("conv_1", "msg_2", 2, 2);

        assertThat(result).extracting(m -> m.get("id")).containsExactly("msg_2");
    }

    @Test
    void erreurFirestore_videSansPropager() {
        wire();
        when(anchorRef.get()).thenReturn(ApiFutures.immediateFailedFuture(new IllegalStateException("down")));

        assertThat(new FirestoreService(firestore).listMessagesAround("conv_1", "msg_2", 2, 2)).isEmpty();
    }
}
