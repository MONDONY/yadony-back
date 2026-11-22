package com.yadony.api.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.yadony.api.common.YadonyBusinessException;
import org.springframework.http.HttpStatus;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("FirebaseContactService : claim declared_phone (numéro déclaré sans SMS)")
class FirebaseContactServiceDeclaredPhoneTest {

    private FirebaseAuth firebaseAuth;
    private FirebaseContactService service;

    @BeforeEach
    void setUp() {
        firebaseAuth = mock(FirebaseAuth.class);
        service = new FirebaseContactService(firebaseAuth);
    }

    private UserRecord record(Map<String, Object> claims) {
        UserRecord record = mock(UserRecord.class);
        when(record.getUid()).thenReturn("uid-1");
        when(record.getEmail()).thenReturn("awa@example.com");
        when(record.getCustomClaims()).thenReturn(claims);
        return record;
    }

    @Test
    @DisplayName("getContact lit le claim et laisse phoneNumber (vérifié par SMS) nul")
    void getContact_readsDeclaredClaim_andLeavesPhoneNumberNull() throws Exception {
        UserRecord record = record(Map.of("declared_phone", "+2250102030405"));
        when(firebaseAuth.getUser("uid-1")).thenReturn(record);

        FirebaseContactService.Contact contact = service.getContact("uid-1");

        assertThat(contact.declaredPhone()).isEqualTo("+2250102030405");
        assertThat(contact.phoneNumber()).isNull();
    }

    @Test
    @DisplayName("setDeclaredPhone conserve les autres claims et ne révoque pas la session")
    void setDeclaredPhone_keepsOtherClaims() throws Exception {
        UserRecord record = record(Map.of("role", "admin"));
        when(firebaseAuth.getUser("uid-1")).thenReturn(record);

        service.setDeclaredPhone("uid-1", "+2250102030405");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> claims = ArgumentCaptor.forClass(Map.class);
        verify(firebaseAuth).setCustomUserClaims(org.mockito.ArgumentMatchers.eq("uid-1"), claims.capture());
        assertThat(claims.getValue())
                .containsEntry("role", "admin")
                .containsEntry("declared_phone", "+2250102030405")
                .hasSize(2);
        verify(firebaseAuth, never()).updateUser(org.mockito.ArgumentMatchers.any());
        verify(firebaseAuth, never()).createCustomToken(anyString());
        verify(firebaseAuth, never()).revokeRefreshTokens(anyString());
    }

    @Test
    @DisplayName("setDeclaredPhone(null) retire uniquement ce claim")
    void setDeclaredPhone_null_removesOnlyThatClaim() throws Exception {
        UserRecord record = record(Map.of("role", "admin", "declared_phone", "+2250102030405"));
        when(firebaseAuth.getUser("uid-1")).thenReturn(record);

        service.setDeclaredPhone("uid-1", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> claims = ArgumentCaptor.forClass(Map.class);
        verify(firebaseAuth).setCustomUserClaims(org.mockito.ArgumentMatchers.eq("uid-1"), claims.capture());
        assertThat(claims.getValue()).containsEntry("role", "admin").doesNotContainKey("declared_phone");
        assertThat(service.getContact("uid-1").declaredPhone()).isNull();
    }

    @Test
    @DisplayName("setDeclaredPhone rafraîchit le cache sans nouvelle lecture Firebase")
    void setDeclaredPhone_refreshesCache() throws Exception {
        UserRecord record = record(Map.of());
        when(firebaseAuth.getUser("uid-1")).thenReturn(record);
        service.getContact("uid-1"); // amorce le cache sans numéro

        service.setDeclaredPhone("uid-1", "+2250102030405");

        assertThat(service.getContact("uid-1").declaredPhone()).isEqualTo("+2250102030405");
        // 1 lecture pour amorcer, 1 lecture des claims dans setDeclaredPhone, aucune après
        verify(firebaseAuth, times(2)).getUser("uid-1");
    }

    @Test
    @DisplayName("Firebase indisponible : setDeclaredPhone ne fait rien")
    void setDeclaredPhone_unavailable_isNoop() throws Exception {
        new FirebaseContactService(null).setDeclaredPhone("uid-1", "+2250102030405");
        verify(firebaseAuth, never()).setCustomUserClaims(anyString(), anyMap());
    }

    @Test
    @DisplayName("setDeclaredPhone : échec de setCustomUserClaims -> 500 firebase-error, cache invalidé")
    void setDeclaredPhone_writeFails_throws500_andInvalidatesCache() throws Exception {
        UserRecord record = record(Map.of());
        when(firebaseAuth.getUser("uid-1")).thenReturn(record);
        service.getContact("uid-1"); // amorce le cache
        doThrow(mock(FirebaseAuthException.class))
                .when(firebaseAuth).setCustomUserClaims(anyString(), anyMap());

        assertThatThrownBy(() -> service.setDeclaredPhone("uid-1", "+2250102030405"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(e.getErrorCode()).isEqualTo("firebase-error");
                });

        service.getContact("uid-1");
        // lecture initiale + lecture dans setDeclaredPhone + relecture après invalidation
        verify(firebaseAuth, times(3)).getUser("uid-1");
    }

    @Test
    @DisplayName("setDeclaredPhone : échec de getUser -> 500 firebase-error, aucune écriture")
    void setDeclaredPhone_readFails_throws500_withoutWriting() throws Exception {
        when(firebaseAuth.getUser("uid-1")).thenThrow(mock(FirebaseAuthException.class));

        assertThatThrownBy(() -> service.setDeclaredPhone("uid-1", "+2250102030405"))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
                    assertThat(e.getErrorCode()).isEqualTo("firebase-error");
                });
        verify(firebaseAuth, never()).setCustomUserClaims(anyString(), anyMap());
    }

    // ── readContactStrict : une panne n'est jamais confondue avec « aucune coordonnée » ──

    @Test
    @DisplayName("readContactStrict relit Firebase en ignorant le cache et le réamorce")
    void readContactStrict_ignoresCache_andRefreshesIt() throws Exception {
        UserRecord before = record(Map.of());
        UserRecord after = record(Map.of("declared_phone", "+2250102030405"));
        when(firebaseAuth.getUser("uid-1")).thenReturn(before, after);
        service.getContact("uid-1"); // amorce le cache sans numéro

        assertThat(service.readContactStrict("uid-1"))
                .hasValueSatisfying(c -> assertThat(c.declaredPhone()).isEqualTo("+2250102030405"));
        assertThat(service.getContact("uid-1").declaredPhone()).isEqualTo("+2250102030405");
        verify(firebaseAuth, times(2)).getUser("uid-1");
    }

    @Test
    @DisplayName("readContactStrict : erreur Firebase -> vide (pas Contact.EMPTY), cache invalidé")
    void readContactStrict_firebaseError_emptyAndCacheInvalidated() throws Exception {
        UserRecord cached = record(Map.of("declared_phone", "+2250102030405"));
        FirebaseAuthException boom = mock(FirebaseAuthException.class);
        when(boom.getAuthErrorCode()).thenReturn(com.google.firebase.auth.AuthErrorCode.EMAIL_ALREADY_EXISTS);
        when(firebaseAuth.getUser("uid-1")).thenReturn(cached).thenThrow(boom).thenReturn(cached);
        service.getContact("uid-1");

        assertThat(service.readContactStrict("uid-1")).isEmpty();
        // L'ancienne valeur n'est plus servie : la lecture suivante repart vers Firebase.
        service.getContact("uid-1");
        verify(firebaseAuth, times(3)).getUser("uid-1");
    }

    @Test
    @DisplayName("readContactStrict : exception inattendue -> vide")
    void readContactStrict_unexpectedException_empty() throws Exception {
        when(firebaseAuth.getUser("uid-1")).thenThrow(new IllegalStateException("réseau"));

        assertThat(service.readContactStrict("uid-1")).isEmpty();
    }

    @Test
    @DisplayName("readContactStrict : UID inconnu de Firebase -> Contact.EMPTY (réponse sûre, pas une panne)")
    void readContactStrict_userNotFound_definitiveEmptyContact() throws Exception {
        FirebaseAuthException notFound = mock(FirebaseAuthException.class);
        when(notFound.getAuthErrorCode()).thenReturn(com.google.firebase.auth.AuthErrorCode.USER_NOT_FOUND);
        when(firebaseAuth.getUser("uid-1")).thenThrow(notFound);

        assertThat(service.readContactStrict("uid-1")).contains(FirebaseContactService.Contact.EMPTY);
    }

    @Test
    @DisplayName("readContactStrict : sans Firebase ou sans UID -> vide")
    void readContactStrict_unavailableOrBlankUid_empty() {
        assertThat(new FirebaseContactService(null).readContactStrict("uid-1")).isEmpty();
        assertThat(service.readContactStrict(" ")).isEmpty();
        assertThat(service.readContactStrict(null)).isEmpty();
    }

    @Test
    @DisplayName("isAvailable : vrai avec FirebaseAuth, faux sans (test/CI)")
    void isAvailable_reflectsFirebaseAuthPresence() {
        assertThat(service.isAvailable()).isTrue();
        assertThat(new FirebaseContactService(null).isAvailable()).isFalse();
    }
}
