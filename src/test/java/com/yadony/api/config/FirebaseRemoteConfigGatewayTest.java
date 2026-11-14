package com.yadony.api.config;

import com.google.firebase.FirebaseApp;
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException;
import com.google.firebase.remoteconfig.Template;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FirebaseRemoteConfigGateway — Firebase absent")
class FirebaseRemoteConfigGatewayTest {

    @Test
    @DisplayName("sans application Firebase, fetch et publish levent une erreur Remote Config (jamais d'appel reseau)")
    void withoutFirebaseApp() {
        Assumptions.assumeTrue(FirebaseApp.getApps().isEmpty());
        FirebaseRemoteConfigGateway gateway = new FirebaseRemoteConfigGateway();

        assertThatThrownBy(gateway::fetch).isInstanceOf(FirebaseRemoteConfigException.class);
        assertThatThrownBy(() -> gateway.publish(new Template("etag")))
                .isInstanceOf(FirebaseRemoteConfigException.class);
    }
}
