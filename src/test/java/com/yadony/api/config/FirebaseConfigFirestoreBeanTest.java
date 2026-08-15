package com.yadony.api.config;

import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.FirestoreClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Garde-fou sur la condition d'existence du bean {@link Firestore}.
 *
 * <p>La condition testait aussi {@code firebase.service-account-path}. Or cette
 * propriété n'est renseignée qu'en développement : en staging et en prod,
 * Firebase s'initialise via {@code GOOGLE_APPLICATION_CREDENTIALS} et la
 * propriété reste vide. Le bean s'annulait donc alors même que Firebase tournait,
 * et tout {@code FirestoreService} retombait sur ses branches « disabled » —
 * sans erreur, sans exception, sans rien de visible.
 *
 * <p>Le seul critère valable est l'initialisation effective de Firebase, quel que
 * soit le mécanisme qui l'a réalisée.
 */
class FirebaseConfigFirestoreBeanTest {

    @Test
    void firestoreBean_isCreated_whenFirebaseIsInitializedWithoutServiceAccountPath() {
        var config = new FirebaseConfig();
        ReflectionTestUtils.setField(config, "serviceAccountPath", "");

        var expected = mock(Firestore.class);

        try (MockedStatic<FirebaseApp> apps = mockStatic(FirebaseApp.class);
             MockedStatic<FirestoreClient> client = mockStatic(FirestoreClient.class)) {

            apps.when(FirebaseApp::getApps).thenReturn(List.of(mock(FirebaseApp.class)));
            client.when(FirestoreClient::getFirestore).thenReturn(expected);

            assertThat(config.firestore())
                    .as("chemin de compte de service vide n'est pas une raison d'annuler"
                            + " le bean : en staging Firebase démarre via GOOGLE_APPLICATION_CREDENTIALS")
                    .isSameAs(expected);
        }
    }

    @Test
    void firestoreBean_isNull_whenFirebaseIsNotInitialized() {
        var config = new FirebaseConfig();
        ReflectionTestUtils.setField(config, "serviceAccountPath", "classpath:firebase.json");

        try (MockedStatic<FirebaseApp> apps = mockStatic(FirebaseApp.class)) {
            apps.when(FirebaseApp::getApps).thenReturn(List.of());

            assertThat(config.firestore())
                    .as("sans Firebase initialisé, le bean reste nul et les appels"
                            + " Firestore sont ignorés proprement (mode test/ci)")
                    .isNull();
        }
    }
}
