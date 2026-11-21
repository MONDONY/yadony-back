package com.yadony.api.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhoneLookupServiceTest {

    private static final String PHONE = "+224621234567";

    @Mock FirebaseContactService firebaseContact;
    @Mock UserRepository userRepository;

    private final DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("cle-de-test");
    private PhoneLookupService service;

    @BeforeEach
    void setUp() {
        service = new PhoneLookupService(firebaseContact, userRepository, hasher, new DeclaredPhoneResolver(hasher));
    }

    private static UserEntity user(String uid) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirebaseUid(uid);
        return u;
    }

    private UserEntity yadonyVerified(String uid) {
        UserEntity u = user(uid);
        u.setDeclaredPhoneHash(hasher.hash(PHONE));
        u.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        return u;
    }

    private UserEntity declaredUser(String uid) {
        UserEntity u = user(uid);
        u.setDeclaredPhoneHash(hasher.hash(PHONE));
        u.setDeclaredPhoneLevel(DeclaredPhoneLevel.DECLARED);
        return u;
    }

    private void ownerNotFound() {
        when(firebaseContact.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
    }

    private void ownerIs(String uid) {
        when(firebaseContact.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.found(uid));
    }

    private void candidates(UserEntity... users) {
        when(userRepository.findTop10ByDeclaredPhoneHash(hasher.hash(PHONE))).thenReturn(List.of(users));
    }

    private void claims(Map<String, String> declaredByUid) {
        when(firebaseContact.getContacts(any())).thenAnswer(inv -> {
            Collection<String> uids = inv.getArgument(0);
            Map<String, FirebaseContactService.Contact> out = new java.util.HashMap<>();
            uids.forEach(uid -> out.put(uid, new FirebaseContactService.Contact(null, null, declaredByUid.get(uid))));
            return out;
        });
    }

    @Test
    void findByPhone_smsVerifiedOwner_found() {
        UserEntity owner = user("uid-sms");
        ownerIs("uid-sms");
        when(userRepository.findByFirebaseUid("uid-sms")).thenReturn(java.util.Optional.of(owner));

        assertThat(service.findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(owner, PhoneMatch.Trust.SMS));
    }

    @Test
    void findByPhone_oneDeclaredAccount_foundWithDeclaredTrust() {
        UserEntity u = declaredUser("uid-d1");
        ownerNotFound();
        candidates(u);
        claims(Map.of("uid-d1", PHONE));

        assertThat(service.findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(u, PhoneMatch.Trust.DECLARED));
    }

    @Test
    void findByPhone_yadonyVerifiedAccount_foundWithYadonyTrust() {
        UserEntity u = yadonyVerified("uid-y");
        ownerNotFound();
        candidates(u);
        claims(Map.of("uid-y", PHONE));

        assertThat(service.findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(u, PhoneMatch.Trust.YADONY));
    }

    @Test
    void findByPhone_twoDeclaredAccounts_ambiguous() {
        UserEntity a = declaredUser("uid-a");
        UserEntity b = declaredUser("uid-b");
        ownerNotFound();
        candidates(a, b);
        claims(Map.of("uid-a", PHONE, "uid-b", PHONE));

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.Ambiguous.class);
    }

    @Test
    void findByPhone_yadonyPlusDeclaredSquatter_ambiguous() {
        UserEntity y = yadonyVerified("uid-y");
        UserEntity d = declaredUser("uid-d");
        ownerNotFound();
        candidates(y, d);
        claims(Map.of("uid-y", PHONE, "uid-d", PHONE));

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.Ambiguous.class);
    }

    @Test
    void findByPhone_smsOwnerBeatsDeclared_found() {
        UserEntity owner = user("uid-sms");
        UserEntity squatter = declaredUser("uid-squat");
        ownerIs("uid-sms");
        when(userRepository.findByFirebaseUid("uid-sms")).thenReturn(java.util.Optional.of(owner));
        lenient().when(userRepository.findTop10ByDeclaredPhoneHash(hasher.hash(PHONE))).thenReturn(List.of(squatter));

        assertThat(service.findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(owner, PhoneMatch.Trust.SMS));
        verify(userRepository, never()).findTop10ByDeclaredPhoneHash(anyString());
    }

    @Test
    void findByPhone_smsOwnerWithoutLocalUser_none() {
        ownerIs("uid-sms");
        when(userRepository.findByFirebaseUid("uid-sms")).thenReturn(java.util.Optional.empty());
        lenient().when(userRepository.findTop10ByDeclaredPhoneHash(hasher.hash(PHONE)))
                .thenReturn(List.of(declaredUser("uid-squat")));

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
        verify(userRepository, never()).findTop10ByDeclaredPhoneHash(anyString());
    }

    @Test
    void findByPhone_unknown_none() {
        ownerNotFound();
        candidates();

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
    }

    @Test
    void findByPhone_firebaseError_failsClosed() {
        when(firebaseContact.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.error());
        lenient().when(userRepository.findTop10ByDeclaredPhoneHash(anyString()))
                .thenReturn(List.of(declaredUser("uid-squat")));

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
        assertThat(service.findAllByPhone(PHONE)).isEmpty();
        verify(userRepository, never()).findTop10ByDeclaredPhoneHash(anyString());
    }

    @Test
    void findByPhone_hasherNotConfigured_skipsDeclaredBranch() {
        DeclaredPhoneHasher off = new DeclaredPhoneHasher("");
        PhoneLookupService s = new PhoneLookupService(firebaseContact, userRepository, off, new DeclaredPhoneResolver(off));
        ownerNotFound();

        assertThat(s.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
        verify(userRepository, never()).findTop10ByDeclaredPhoneHash(any());
    }

    @Test
    void findByPhone_staleHashClaimDiffers_dropped() {
        UserEntity stale = declaredUser("uid-stale");
        ownerNotFound();
        candidates(stale);
        claims(Map.of("uid-stale", "+224629999999"));

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
    }

    @Test
    void findByPhone_candidateWithSmsNumber_dropped() {
        UserEntity verified = declaredUser("uid-v");
        ownerNotFound();
        candidates(verified);
        when(firebaseContact.getContacts(any())).thenReturn(
                Map.of("uid-v", new FirebaseContactService.Contact("+33612345678", null, PHONE)));

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
    }

    @Test
    void findByPhone_oneStaleOneValid_found() {
        UserEntity stale = declaredUser("uid-stale");
        UserEntity ok = declaredUser("uid-ok");
        ownerNotFound();
        candidates(stale, ok);
        claims(Map.of("uid-ok", PHONE));

        assertThat(service.findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(ok, PhoneMatch.Trust.DECLARED));
    }

    @Test
    void findByPhone_manyCandidates_singleBatchedFirebaseRead() {
        UserEntity a = declaredUser("uid-a");
        UserEntity b = declaredUser("uid-b");
        UserEntity c = declaredUser("uid-c");
        ownerNotFound();
        candidates(a, b, c);
        claims(Map.of("uid-a", PHONE, "uid-b", PHONE, "uid-c", PHONE));

        service.findByPhone(PHONE);

        verify(firebaseContact, times(1)).getContacts(any());
        verify(firebaseContact, never()).getContact(anyString());
    }

    @Test
    void findAllByPhone_smsOwnerPlusSquatters_adminSeesAll() {
        UserEntity owner = user("uid-sms");
        UserEntity squatter = declaredUser("uid-squat");
        ownerIs("uid-sms");
        when(userRepository.findByFirebaseUid("uid-sms")).thenReturn(java.util.Optional.of(owner));
        candidates(squatter);
        claims(Map.of("uid-squat", PHONE));

        assertThat(service.findAllByPhone(PHONE)).containsExactly(owner, squatter);
    }

    @Test
    void findAllByPhone_twoDeclared_adminSeesBoth() {
        UserEntity a = declaredUser("uid-a");
        UserEntity b = declaredUser("uid-b");
        ownerNotFound();
        candidates(a, b);
        claims(Map.of("uid-a", PHONE, "uid-b", PHONE));

        assertThat(service.findAllByPhone(PHONE)).containsExactly(a, b);
    }
}
