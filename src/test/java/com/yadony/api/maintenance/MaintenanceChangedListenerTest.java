package com.yadony.api.maintenance;

import com.google.api.core.ApiFutures;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaintenanceChangedListenerTest {

    private final MaintenanceCache cache = mock(MaintenanceCache.class);
    private final Firestore firestore = mock(Firestore.class);
    private final CollectionReference collection = mock(CollectionReference.class);
    private final DocumentReference document = mock(DocumentReference.class);

    @Test
    @SuppressWarnings("unchecked")
    void changement_videLeCache_etRecopieDansFirestore() {
        Instant end = Instant.parse("2026-10-16T14:30:00Z");
        when(cache.snapshot()).thenReturn(new MaintenanceSnapshot(true, "Pause", end, null, Set.of("uid-1")));
        when(firestore.collection("config")).thenReturn(collection);
        when(collection.document("maintenance")).thenReturn(document);
        when(document.set(anyMap())).thenReturn(ApiFutures.immediateFuture(null));

        new MaintenanceChangedListener(cache, firestore).onChanged(new MaintenanceChangedEvent());

        verify(cache).evict();
        ArgumentCaptor<Map<String, Object>> doc = ArgumentCaptor.forClass(Map.class);
        verify(document).set(doc.capture());
        assertThat(doc.getValue())
                .containsEntry("enabled", true)
                .containsEntry("message", "Pause")
                .containsEntry("estimatedEndAt", "2026-10-16T14:30:00Z")
                .containsKey("updatedAt")
                .doesNotContainKey("exemptFirebaseUids");
    }

    @Test
    void sansFirestore_rienNeCasse() {
        assertThatCode(() -> new MaintenanceChangedListener(cache, null).onStartup()).doesNotThrowAnyException();
    }

    @Test
    void echecFirestore_nonBloquant() {
        when(cache.snapshot()).thenReturn(MaintenanceSnapshot.OFF);
        when(firestore.collection("config")).thenThrow(new IllegalStateException("quota"));
        assertThatCode(() -> new MaintenanceChangedListener(cache, firestore).onStartup()).doesNotThrowAnyException();
    }
}
