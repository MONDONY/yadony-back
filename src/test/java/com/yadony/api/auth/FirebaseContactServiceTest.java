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
import static org.mockito.Mockito.doThrow;
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

    // ── YADONY-FLUTTER-PROD-Y : porteur d'un numéro, suppression, retrait du numéro ──

    private static FirebaseAuthException authError(com.google.firebase.auth.AuthErrorCode code) {
        FirebaseAuthException e = mock(FirebaseAuthException.class);
        when(e.getAuthErrorCode()).thenReturn(code);
        return e;
    }

    private static com.google.firebase.auth.UserInfo provider(String id) {
        com.google.firebase.auth.UserInfo info = mock(com.google.firebase.auth.UserInfo.class);
        when(info.getProviderId()).thenReturn(id);
        return info;
    }

    @Test
    @DisplayName("findPhoneHolder → uid, email et fournisseurs ; phoneOnly vrai sans email ni autre fournisseur")
    void findPhoneHolder_phoneOnly() throws Exception {
        UserRecord record = mock(UserRecord.class);
        when(record.getUid()).thenReturn("uid-fantome");
        com.google.firebase.auth.UserInfo phone = provider("phone");
        when(record.getProviderData()).thenReturn(new com.google.firebase.auth.UserInfo[] {phone});
        when(firebaseAuth.getUserByPhoneNumber("+221701234567")).thenReturn(record);

        FirebaseContactService.PhoneHolder holder = service.findPhoneHolder("+221701234567").orElseThrow();

        assertThat(holder.uid()).isEqualTo("uid-fantome");
        assertThat(holder.providerIds()).containsExactly("phone");
        assertThat(holder.phoneOnly()).isTrue();
    }

    @Test
    @DisplayName("findPhoneHolder → phoneOnly faux avec Google ou avec un email")
    void findPhoneHolder_otherIdentity() throws Exception {
        UserRecord google = mock(UserRecord.class);
        when(google.getUid()).thenReturn("uid-google");
        com.google.firebase.auth.UserInfo phone = provider("phone");
        com.google.firebase.auth.UserInfo googleProvider = provider("google.com");
        when(google.getProviderData()).thenReturn(new com.google.firebase.auth.UserInfo[] {phone, googleProvider});
        when(firebaseAuth.getUserByPhoneNumber("+221701234567")).thenReturn(google);

        assertThat(service.findPhoneHolder("+221701234567").orElseThrow().phoneOnly()).isFalse();
        assertThat(new FirebaseContactService.PhoneHolder("u", "awa@example.com", Set.of("phone")).phoneOnly())
                .isFalse();
        assertThat(new FirebaseContactService.PhoneHolder("u", " ", Set.of()).phoneOnly()).isTrue();
    }

    @Test
    @DisplayName("findPhoneHolder → vide si aucun compte, en cas de panne, sans Firebase ou sans numéro")
    void findPhoneHolder_empty() throws Exception {
        FirebaseAuthException notFound = authError(com.google.firebase.auth.AuthErrorCode.USER_NOT_FOUND);
        when(firebaseAuth.getUserByPhoneNumber("+221700000001")).thenThrow(notFound);
        FirebaseAuthException outage = authError(null);
        when(firebaseAuth.getUserByPhoneNumber("+221700000002")).thenThrow(outage);
        UserRecord noProviders = mock(UserRecord.class);
        when(noProviders.getUid()).thenReturn("uid-nu");
        when(firebaseAuth.getUserByPhoneNumber("+221700000003")).thenReturn(noProviders);

        assertThat(service.findPhoneHolder("+221700000001")).isEmpty();
        assertThat(service.findPhoneHolder("+221700000002")).isEmpty();
        assertThat(service.findPhoneHolder("+221700000003").orElseThrow().phoneOnly()).isTrue();
        assertThat(service.findPhoneHolder(" ")).isEmpty();
        assertThat(new FirebaseContactService(null).findPhoneHolder("+221701234567")).isEmpty();
    }

    @Test
    @DisplayName("deleteAccount → DELETED, ALREADY_GONE, FAILED ou SKIPPED selon Firebase")
    void deleteAccount_outcomes() throws Exception {
        assertThat(service.deleteAccount("uid-ok")).isEqualTo(FirebaseContactService.DeletionOutcome.DELETED);

        FirebaseAuthException notFound = authError(com.google.firebase.auth.AuthErrorCode.USER_NOT_FOUND);
        doThrow(notFound).when(firebaseAuth).deleteUser("uid-parti");
        assertThat(service.deleteAccount("uid-parti")).isEqualTo(FirebaseContactService.DeletionOutcome.ALREADY_GONE);

        FirebaseAuthException outage = authError(null);
        doThrow(outage).when(firebaseAuth).deleteUser("uid-ko");
        assertThat(service.deleteAccount("uid-ko")).isEqualTo(FirebaseContactService.DeletionOutcome.FAILED);

        assertThat(service.deleteAccount(null)).isEqualTo(FirebaseContactService.DeletionOutcome.SKIPPED);
        assertThat(new FirebaseContactService(null).deleteAccount("uid-ok"))
                .isEqualTo(FirebaseContactService.DeletionOutcome.SKIPPED);
    }

    @Test
    @DisplayName("clearPhone → retire le numéro (vrai), compte disparu (vrai), panne (faux), sans Firebase (faux)")
    void clearPhone_outcomes() throws Exception {
        assertThat(service.clearPhone("uid-ok")).isTrue();
        verify(firebaseAuth).updateUser(any(UserRecord.UpdateRequest.class));

        FirebaseAuthException notFound = authError(com.google.firebase.auth.AuthErrorCode.USER_NOT_FOUND);
        FirebaseAuthException outage = authError(null);
        when(firebaseAuth.updateUser(any(UserRecord.UpdateRequest.class))).thenThrow(notFound).thenThrow(outage);
        assertThat(service.clearPhone("uid-parti")).isTrue();
        assertThat(service.clearPhone("uid-ko")).isFalse();

        assertThat(service.clearPhone(null)).isFalse();
        assertThat(new FirebaseContactService(null).clearPhone("uid-ok")).isFalse();
    }
}
