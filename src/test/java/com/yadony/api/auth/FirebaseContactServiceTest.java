package com.yadony.api.auth;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.google.firebase.auth.GetUsersResult;
import com.google.firebase.auth.UserIdentifier;

import java.util.List;
import java.util.Map;
import java.util.Set;

@DisplayName("FirebaseContactService — contact via Firebase (jamais stocké en base)")
class FirebaseContactServiceTest {

    private FirebaseAuth firebaseAuth;
    private FirebaseContactService service;

    @BeforeEach
    void setUp() {
        firebaseAuth = mock(FirebaseAuth.class);
        service = new FirebaseContactService(firebaseAuth);
    }

    @Test
    @DisplayName("getContact → phone + email depuis Firebase, mis en cache (1 seul appel)")
    void getContact_returnsAndCaches() throws Exception {
        UserRecord record = mock(UserRecord.class);
        when(record.getPhoneNumber()).thenReturn("+221701234567");
        when(record.getEmail()).thenReturn("awa@example.com");
        when(firebaseAuth.getUser("uid-1")).thenReturn(record);

        FirebaseContactService.Contact c1 = service.getContact("uid-1");
        FirebaseContactService.Contact c2 = service.getContact("uid-1");

        assertThat(c1.phoneNumber()).isEqualTo("+221701234567");
        assertThat(c1.email()).isEqualTo("awa@example.com");
        assertThat(c2).isEqualTo(c1);
        verify(firebaseAuth, times(1)).getUser("uid-1"); // 2e lecture servie par le cache
    }

    @Test
    @DisplayName("getFreshContact → ignore le cache : un email ajouté entre-temps est vu tout de suite")
    void getFreshContact_bypassesStaleCache() throws Exception {
        UserRecord before = mock(UserRecord.class);
        UserRecord after = mock(UserRecord.class);
        when(after.getEmail()).thenReturn("awa@example.com");
        when(firebaseAuth.getUser("uid-1")).thenReturn(before, after);

        assertThat(service.getContact("uid-1").email()).isNull();
        assertThat(service.getFreshContact("uid-1").email()).isEqualTo("awa@example.com");
        assertThat(service.getContact("uid-1").email()).isEqualTo("awa@example.com");
        verify(firebaseAuth, times(2)).getUser("uid-1");
    }

    @Test
    @DisplayName("getContact → EMPTY (dégradation gracieuse) si Firebase échoue")
    void getContact_gracefulOnFailure() throws Exception {
        when(firebaseAuth.getUser("uid-x")).thenThrow(mock(FirebaseAuthException.class));

        assertThat(service.getContact("uid-x")).isEqualTo(FirebaseContactService.Contact.EMPTY);
    }

    @Test
    @DisplayName("getContact → EMPTY si UID null/vide, sans appeler Firebase")
    void getContact_nullUid() throws Exception {
        assertThat(service.getContact(null)).isEqualTo(FirebaseContactService.Contact.EMPTY);
        assertThat(service.getContact("")).isEqualTo(FirebaseContactService.Contact.EMPTY);
        verify(firebaseAuth, times(0)).getUser(anyString());
    }

    @Test
    @DisplayName("findUidByEmail → UID si trouvé, vide sinon")
    void findUidByEmail() throws Exception {
        UserRecord record = mock(UserRecord.class);
        when(record.getUid()).thenReturn("uid-42");
        when(firebaseAuth.getUserByEmail("awa@example.com")).thenReturn(record);
        when(firebaseAuth.getUserByEmail("ghost@example.com")).thenThrow(mock(FirebaseAuthException.class));

        assertThat(service.findUidByEmail("awa@example.com")).contains("uid-42");
        assertThat(service.findUidByEmail("ghost@example.com")).isEmpty();
        assertThat(service.findUidByEmail(null)).isEmpty();
    }

    @Test
    @DisplayName("getContacts → un seul aller-retour Firebase pour plusieurs UID")
    void getContacts_batchesInOneCall() throws Exception {
        GetUsersResult batch = usersResult(record("uid-1", "+221701111111", "a@example.com"),
                                          record("uid-2", "+221702222222", "b@example.com"));
        when(firebaseAuth.getUsers(anyList())).thenReturn(batch);

        Map<String, FirebaseContactService.Contact> contacts =
                service.getContacts(List.of("uid-1", "uid-2"));

        assertThat(contacts.get("uid-1").phoneNumber()).isEqualTo("+221701111111");
        assertThat(contacts.get("uid-2").email()).isEqualTo("b@example.com");
        verify(firebaseAuth, times(1)).getUsers(anyList());
    }

    @Test
    @DisplayName("getContacts → alimente le cache, une lecture unitaire suivante n'appelle plus Firebase")
    void getContacts_populatesCache() throws Exception {
        GetUsersResult batch = usersResult(record("uid-1", "+221701111111", "a@example.com"));
        when(firebaseAuth.getUsers(anyList())).thenReturn(batch);

        service.getContacts(List.of("uid-1"));
        FirebaseContactService.Contact c = service.getContact("uid-1");

        assertThat(c.phoneNumber()).isEqualTo("+221701111111");
        verify(firebaseAuth, never()).getUser(anyString());
    }

    @Test
    @DisplayName("getContacts → EMPTY pour les UID absents de la réponse Firebase")
    void getContacts_missingUidsFallBackToEmpty() throws Exception {
        GetUsersResult empty = usersResult();
        when(firebaseAuth.getUsers(anyList())).thenReturn(empty);

        Map<String, FirebaseContactService.Contact> contacts =
                service.getContacts(List.of("uid-inconnu"));

        assertThat(contacts.get("uid-inconnu")).isEqualTo(FirebaseContactService.Contact.EMPTY);
    }

    @Test
    @DisplayName("getContacts → vide et sans appel si la collection est vide")
    void getContacts_emptyInput() throws Exception {
        assertThat(service.getContacts(List.of())).isEmpty();
        assertThat(service.getContacts(null)).isEmpty();
        verify(firebaseAuth, never()).getUsers(anyList());
    }

    @Test
    @DisplayName("evict → la lecture suivante refrappe Firebase")
    void evict_forcesRefetch() throws Exception {
        UserRecord r = record("uid-1", "+221701111111", null);
        when(firebaseAuth.getUser("uid-1")).thenReturn(r);

        service.getContact("uid-1");
        service.evict("uid-1");
        service.getContact("uid-1");

        verify(firebaseAuth, times(2)).getUser("uid-1");
    }

    @Test
    @DisplayName("updateEmail écrit dans Firebase et réamorce le cache sans relire")
    void updates_writeToFirebaseAndRefreshCache() throws Exception {
        UserRecord avant = record("uid-1", "+221701111111", "avant@example.com");
        UserRecord apres = record("uid-1", "+221709999999", "apres@example.com");
        when(firebaseAuth.getUser("uid-1")).thenReturn(avant);
        when(firebaseAuth.updateUser(any())).thenReturn(apres);

        service.getContact("uid-1");
        service.updateEmail("uid-1", "apres@example.com");

        assertThat(service.getContact("uid-1").email()).isEqualTo("apres@example.com");
        verify(firebaseAuth, times(1)).updateUser(any());
        // La réponse de updateUser porte déjà les coordonnées à jour : la relecture
        // d'après écriture ne doit pas repartir vers Firebase.
        verify(firebaseAuth, times(1)).getUser("uid-1");
    }

    @Test
    @DisplayName("Firebase indisponible (test/CI) → dégradation complète, jamais d'exception")
    void firebaseUnavailable_degradesGracefully() {
        FirebaseContactService offline = new FirebaseContactService(null);

        assertThat(offline.getContact("uid-1")).isEqualTo(FirebaseContactService.Contact.EMPTY);
        // getContacts garantit une entrée par UID demandé, même hors ligne : les
        // appelants n'ont pas à distinguer « absent » de « Firebase injoignable ».
        assertThat(offline.getContacts(List.of("uid-1")))
                .containsExactly(org.assertj.core.api.Assertions.entry(
                        "uid-1", FirebaseContactService.Contact.EMPTY));
        assertThat(offline.findUidByEmail("awa@example.com")).isEmpty();
        assertThat(offline.findUidByPhone("+221701234567")).isEmpty();
        // Les écritures sont des no-op : rien à propager, aucun compte Firebase à joindre
        offline.updateEmail("uid-1", "awa@example.com");
        offline.updatePhone("uid-1", "+221701234567");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static UserRecord record(String uid, String phone, String email) {
        UserRecord r = mock(UserRecord.class);
        org.mockito.Mockito.lenient().when(r.getUid()).thenReturn(uid);
        org.mockito.Mockito.lenient().when(r.getPhoneNumber()).thenReturn(phone);
        org.mockito.Mockito.lenient().when(r.getEmail()).thenReturn(email);
        return r;
    }

    private static GetUsersResult usersResult(UserRecord... records) {
        GetUsersResult result = mock(GetUsersResult.class);
        org.mockito.Mockito.doReturn(Set.of(records)).when(result).getUsers();
        return result;
    }

    @Test
    @DisplayName("findUidByPhone → UID si trouvé, vide sinon")
    void findUidByPhone() throws Exception {
        UserRecord record = mock(UserRecord.class);
        when(record.getUid()).thenReturn("uid-7");
        when(firebaseAuth.getUserByPhoneNumber("+221701234567")).thenReturn(record);

        assertThat(service.findUidByPhone("+221701234567")).contains("uid-7");
        assertThat(service.findUidByPhone("")).isEmpty();
    }

    // ── issueSessionToken (FLUTTER-4C) ───────────────────────────────────────

    @Test
    @DisplayName("issueSessionToken → jeton personnalisé sur le même UID")
    void issueSessionToken_returnsCustomToken() throws Exception {
        when(firebaseAuth.createCustomToken("uid-1")).thenReturn("custom-token");

        assertThat(service.issueSessionToken("uid-1")).isEqualTo("custom-token");
    }

    @Test
    @DisplayName("issueSessionToken → null si Firebase échoue (jamais bloquant)")
    void issueSessionToken_nullOnFailure() throws Exception {
        when(firebaseAuth.createCustomToken("uid-1")).thenThrow(mock(FirebaseAuthException.class));

        assertThat(service.issueSessionToken("uid-1")).isNull();
    }

    @Test
    @DisplayName("issueSessionToken → null sans Firebase ou sans UID")
    void issueSessionToken_nullWhenUnavailable() throws Exception {
        assertThat(new FirebaseContactService(null).issueSessionToken("uid-1")).isNull();
        assertThat(service.issueSessionToken(null)).isNull();
        verify(firebaseAuth, never()).createCustomToken(anyString());
    }
}
