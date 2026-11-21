package com.yadony.api.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
}
