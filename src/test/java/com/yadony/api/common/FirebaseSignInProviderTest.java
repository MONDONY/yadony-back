package com.yadony.api.common;

import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("FirebaseSignInProvider — lecture du claim sign_in_provider")
class FirebaseSignInProviderTest {

    private static FirebaseToken tokenWithProvider(Object provider) {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getClaims()).thenReturn(Map.of("firebase", Map.of("sign_in_provider", provider)));
        return token;
    }

    @Test
    @DisplayName("session anonyme → isAnonymous true")
    void anonymousToken() {
        assertThat(FirebaseSignInProvider.isAnonymous(tokenWithProvider("anonymous"))).isTrue();
        assertThat(FirebaseSignInProvider.of(tokenWithProvider("anonymous"))).isEqualTo("anonymous");
    }

    @Test
    @DisplayName("session téléphone → isAnonymous false")
    void phoneToken() {
        assertThat(FirebaseSignInProvider.isAnonymous(tokenWithProvider("phone"))).isFalse();
        assertThat(FirebaseSignInProvider.of(tokenWithProvider("phone"))).isEqualTo("phone");
    }

    @Test
    @DisplayName("token null → jamais anonyme, jamais d'exception")
    void nullToken() {
        assertThat(FirebaseSignInProvider.isAnonymous(null)).isFalse();
        assertThat(FirebaseSignInProvider.of(null)).isNull();
    }

    @Test
    @DisplayName("claim firebase absent → jamais anonyme")
    void missingClaim() {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getClaims()).thenReturn(Map.of());
        assertThat(FirebaseSignInProvider.isAnonymous(token)).isFalse();
        assertThat(FirebaseSignInProvider.of(token)).isNull();
    }

    @Test
    @DisplayName("claim de type inattendu → jamais anonyme (aucune ClassCastException)")
    void malformedClaim() {
        FirebaseToken token = mock(FirebaseToken.class);
        when(token.getClaims()).thenReturn(Map.of("firebase", "pas-une-map"));
        assertThat(FirebaseSignInProvider.isAnonymous(token)).isFalse();

        assertThat(FirebaseSignInProvider.isAnonymous(tokenWithProvider(42))).isFalse();
    }
}
