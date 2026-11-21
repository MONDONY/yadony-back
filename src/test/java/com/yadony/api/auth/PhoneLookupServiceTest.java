package com.yadony.api.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PhoneLookupServiceTest {

    private static final String PHONE = "+224621234567";

    @Mock FirebaseContactService firebaseContact;
    @Mock UserRepository userRepository;

    private final DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("cle-de-test");
    private PhoneLookupService service() {
        return new PhoneLookupService(firebaseContact, userRepository, hasher);
    }

    private static UserEntity user(String uid) {
        UserEntity u = new UserEntity();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFirebaseUid(uid);
        return u;
    }

    private void declared(UserEntity u, String claimPhone) {
        when(firebaseContact.getContact(u.getFirebaseUid()))
                .thenReturn(new FirebaseContactService.Contact(null, null, claimPhone));
    }

    @Test
    void findByPhone_smsVerifiedOwner_found() {
        UserEntity owner = user("uid-sms");
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.of("uid-sms"));
        when(userRepository.findByFirebaseUid("uid-sms")).thenReturn(Optional.of(owner));

        assertThat(service().findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(owner));
        verify(userRepository, never()).findAllByDeclaredPhoneHash(anyString());
    }

    @Test
    void findByPhone_oneDeclaredAccount_found() {
        UserEntity u = user("uid-d1");
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.empty());
        when(userRepository.findAllByDeclaredPhoneHash(hasher.hash(PHONE))).thenReturn(List.of(u));
        declared(u, PHONE);

        assertThat(service().findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(u));
    }

    @Test
    void findByPhone_twoDeclaredAccounts_ambiguous() {
        UserEntity a = user("uid-a");
        UserEntity b = user("uid-b");
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.empty());
        when(userRepository.findAllByDeclaredPhoneHash(hasher.hash(PHONE))).thenReturn(List.of(a, b));
        declared(a, PHONE);
        declared(b, PHONE);

        assertThat(service().findByPhone(PHONE)).isInstanceOf(PhoneMatch.Ambiguous.class);
        assertThat(service().findAllByPhone(PHONE)).containsExactly(a, b);
    }

    @Test
    void findByPhone_smsOwnerBeatsDeclared_found() {
        UserEntity owner = user("uid-sms");
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.of("uid-sms"));
        when(userRepository.findByFirebaseUid("uid-sms")).thenReturn(Optional.of(owner));

        assertThat(service().findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(owner));
        verify(userRepository, never()).findAllByDeclaredPhoneHash(any());
    }

    @Test
    void findByPhone_unknown_none() {
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.empty());
        when(userRepository.findAllByDeclaredPhoneHash(hasher.hash(PHONE))).thenReturn(List.of());

        assertThat(service().findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
    }

    @Test
    void findByPhone_hasherNotConfigured_skipsDeclaredBranch() {
        PhoneLookupService off = new PhoneLookupService(firebaseContact, userRepository, new DeclaredPhoneHasher(""));
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.empty());

        assertThat(off.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
        verify(userRepository, never()).findAllByDeclaredPhoneHash(any());
    }

    @Test
    void findByPhone_staleHashClaimDiffers_dropped() {
        UserEntity stale = user("uid-stale");
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.empty());
        when(userRepository.findAllByDeclaredPhoneHash(hasher.hash(PHONE))).thenReturn(List.of(stale));
        declared(stale, "+224629999999");

        assertThat(service().findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
    }

    @Test
    void findByPhone_candidateWithSmsNumber_dropped() {
        UserEntity verified = user("uid-v");
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.empty());
        when(userRepository.findAllByDeclaredPhoneHash(hasher.hash(PHONE))).thenReturn(List.of(verified));
        when(firebaseContact.getContact("uid-v"))
                .thenReturn(new FirebaseContactService.Contact("+33612345678", null, PHONE));

        assertThat(service().findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
    }

    @Test
    void findByPhone_oneStaleOneValid_found() {
        UserEntity stale = user("uid-stale");
        UserEntity ok = user("uid-ok");
        when(firebaseContact.findUidByPhone(PHONE)).thenReturn(Optional.empty());
        when(userRepository.findAllByDeclaredPhoneHash(hasher.hash(PHONE))).thenReturn(List.of(stale, ok));
        declared(stale, null);
        declared(ok, PHONE);

        assertThat(service().findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(ok));
    }
}
