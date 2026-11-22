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

    // ── trustedDeclaredPhone : seul un numéro « Vérifié par Yadony » concordant ET non contesté est de confiance ──

    @Test
    void trustedDeclaredPhone_yadonyVerifiedAndSoleClaimant_returnsTheNumber() {
        UserEntity u = yadonyVerified("u");
        ownerNotFound();
        candidates(u);
        claims(Map.of("u", PHONE));

        assertThat(service.trustedDeclaredPhone(u, new FirebaseContactService.Contact(null, null, PHONE)))
                .contains(PHONE);
    }

    @Test
    void trustedDeclaredPhone_smsOwnerElsewhere_isEmpty() {
        UserEntity u = yadonyVerified("u");
        UserEntity smsOwner = user("uid-sms");
        ownerIs("uid-sms");
        when(userRepository.findByFirebaseUid("uid-sms")).thenReturn(java.util.Optional.of(smsOwner));

        assertThat(service.trustedDeclaredPhone(u, new FirebaseContactService.Contact(null, null, PHONE))).isEmpty();
    }

    @Test
    void trustedDeclaredPhone_twoYadonyAccountsOnSameNumber_isEmpty() {
        UserEntity u = yadonyVerified("u");
        UserEntity other = yadonyVerified("o");
        ownerNotFound();
        candidates(u, other);
        claims(Map.of("u", PHONE, "o", PHONE));

        assertThat(service.trustedDeclaredPhone(u, new FirebaseContactService.Contact(null, null, PHONE))).isEmpty();
    }

    @Test
    void trustedDeclaredPhone_yadonyAccountWinsOverPlainDeclarant_returnsTheNumber() {
        UserEntity u = yadonyVerified("u");
        UserEntity plain = declaredUser("p");
        ownerNotFound();
        candidates(u, plain);
        claims(Map.of("u", PHONE, "p", PHONE));

        assertThat(service.trustedDeclaredPhone(u, new FirebaseContactService.Contact(null, null, PHONE)))
                .contains(PHONE);
    }

    @Test
    void trustedDeclaredPhone_plainDeclared_isEmpty() {
        assertThat(service.trustedDeclaredPhone(declaredUser("u"),
                new FirebaseContactService.Contact(null, null, PHONE))).isEmpty();
    }

    @Test
    void trustedDeclaredPhone_claimChangedSinceValidation_isEmpty() {
        assertThat(service.trustedDeclaredPhone(yadonyVerified("u"),
                new FirebaseContactService.Contact(null, null, "+224629999999"))).isEmpty();
    }

    @Test
    void trustedDeclaredPhone_smsPhoneExists_isEmpty() {
        assertThat(service.trustedDeclaredPhone(yadonyVerified("u"),
                new FirebaseContactService.Contact("+224621111111", null, PHONE))).isEmpty();
    }

    @Test
    void trustedDeclaredPhone_noContact_isEmpty() {
        assertThat(service.trustedDeclaredPhone(yadonyVerified("u"), null)).isEmpty();
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

    /**
     * Comme la base : la lecture mixte rend tous les déclarants, la lecture dédiée seulement les comptes
     * validés par Yadony. Lenient : selon le chemin (findByPhone ou findAllByPhone), une seule sert.
     */
    private void candidates(UserEntity... users) {
        lenient().when(userRepository.findTop10ByDeclaredPhoneHash(hasher.hash(PHONE))).thenReturn(List.of(users));
        lenient().when(userRepository.findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel(
                        hasher.hash(PHONE), DeclaredPhoneLevel.YADONY_VERIFIED))
                .thenReturn(java.util.Arrays.stream(users)
                        .filter(u -> u.getDeclaredPhoneLevel() == DeclaredPhoneLevel.YADONY_VERIFIED)
                        .toList());
    }

    // ── Constat 1 : des déclarants en nombre ne masquent jamais le compte validé par Yadony ──

    @Test
    void findByPhone_twelveDeclarantsPlusOneYadony_foundYadony() {
        UserEntity y = yadonyVerified("uid-y");
        List<UserEntity> declarants = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            declarants.add(declaredUser("uid-d" + i));
        }
        ownerNotFound();
        // La lecture mixte plafonnée et sans ordre peut rendre 10 déclarants sans le compte validé.
        lenient().when(userRepository.findTop10ByDeclaredPhoneHash(hasher.hash(PHONE)))
                .thenReturn(declarants.subList(0, 10));
        when(userRepository.findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel(
                hasher.hash(PHONE), DeclaredPhoneLevel.YADONY_VERIFIED)).thenReturn(List.of(y));
        claims(Map.of("uid-y", PHONE));

        assertThat(service.findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(y, PhoneMatch.Trust.YADONY));
        assertThat(service.trustedDeclaredPhone(y, new FirebaseContactService.Contact(null, null, PHONE)))
                .contains(PHONE);
    }

    @Test
    void findAllByPhone_twelveDeclarantsPlusOneYadony_yadonyListedFirst() {
        UserEntity y = yadonyVerified("uid-y");
        List<UserEntity> declarants = new java.util.ArrayList<>();
        Map<String, String> claimed = new java.util.HashMap<>(Map.of("uid-y", PHONE));
        for (int i = 0; i < 12; i++) {
            declarants.add(declaredUser("uid-d" + i));
            claimed.put("uid-d" + i, PHONE);
        }
        ownerNotFound();
        when(userRepository.findTop10ByDeclaredPhoneHash(hasher.hash(PHONE))).thenReturn(declarants.subList(0, 10));
        when(userRepository.findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel(
                hasher.hash(PHONE), DeclaredPhoneLevel.YADONY_VERIFIED)).thenReturn(List.of(y));
        claims(claimed);

        List<UserEntity> all = service.findAllByPhone(PHONE);

        assertThat(all).first().isEqualTo(y);
        assertThat(all).hasSize(PhoneLookupService.MAX_CANDIDATES).doesNotHaveDuplicates();
        verify(firebaseContact, times(1)).getContacts(any());
    }

    @Test
    void findByPhone_declarantsOnly_mixedReadNeverUsed() {
        ownerNotFound();
        candidates(declaredUser("uid-a"));

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
        verify(userRepository, never()).findTop10ByDeclaredPhoneHash(anyString());
    }

    // ── Constat 6 : cache court de trustedDeclaredPhone ──

    @Test
    void trustedDeclaredPhone_twoCalls_firebaseHitOnce() {
        UserEntity u = yadonyVerified("u");
        ownerNotFound();
        candidates(u);
        claims(Map.of("u", PHONE));
        FirebaseContactService.Contact contact = new FirebaseContactService.Contact(null, null, PHONE);

        assertThat(service.trustedDeclaredPhone(u, contact)).contains(PHONE);
        assertThat(service.trustedDeclaredPhone(u, contact)).contains(PHONE);

        verify(firebaseContact, times(1)).findPhoneOwner(PHONE);
        verify(firebaseContact, times(1)).getContacts(any());
    }

    @Test
    void trustedDeclaredPhone_levelOrHashChange_bypassesCache() {
        UserEntity u = yadonyVerified("u");
        ownerNotFound();
        candidates(u);
        claims(Map.of("u", PHONE));
        FirebaseContactService.Contact contact = new FirebaseContactService.Contact(null, null, PHONE);
        assertThat(service.trustedDeclaredPhone(u, contact)).contains(PHONE);

        // Redéclaration : niveau retombé à DECLARED, plus de confiance, sans relire le cache.
        u.setDeclaredPhoneLevel(DeclaredPhoneLevel.DECLARED);
        assertThat(service.trustedDeclaredPhone(u, contact)).isEmpty();

        // Nouveau numéro validé : autre empreinte, autre claim, donc autre clé : Firebase est relu.
        String other = "+224629999999";
        u.setDeclaredPhoneLevel(DeclaredPhoneLevel.YADONY_VERIFIED);
        u.setDeclaredPhoneHash(hasher.hash(other));
        when(firebaseContact.findPhoneOwner(other)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
        when(userRepository.findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel(
                hasher.hash(other), DeclaredPhoneLevel.YADONY_VERIFIED)).thenReturn(List.of(u));
        claims(Map.of("u", other));
        assertThat(service.trustedDeclaredPhone(u, new FirebaseContactService.Contact(null, null, other)))
                .contains(other);

        verify(firebaseContact, times(1)).findPhoneOwner(PHONE);
        verify(firebaseContact, times(1)).findPhoneOwner(other);
    }

    @Test
    void trustedDeclaredPhone_ownerLookupError_notCached() {
        UserEntity u = yadonyVerified("u");
        when(firebaseContact.findPhoneOwner(PHONE)).thenReturn(
                FirebaseContactService.PhoneOwnerLookup.error(), FirebaseContactService.PhoneOwnerLookup.notFound());
        candidates(u);
        claims(Map.of("u", PHONE));
        FirebaseContactService.Contact contact = new FirebaseContactService.Contact(null, null, PHONE);

        assertThat(service.trustedDeclaredPhone(u, contact)).isEmpty();
        // La panne n'est pas retenue : l'appel suivant relit Firebase et trouve le numéro.
        assertThat(service.trustedDeclaredPhone(u, contact)).contains(PHONE);
        verify(firebaseContact, times(2)).findPhoneOwner(PHONE);
    }

    @Test
    void trustedDeclaredPhone_smsOwnerElsewhere_definitiveRefusalCached() {
        UserEntity u = yadonyVerified("u");
        ownerIs("uid-sms");
        when(userRepository.findByFirebaseUid("uid-sms")).thenReturn(java.util.Optional.of(user("uid-sms")));
        FirebaseContactService.Contact contact = new FirebaseContactService.Contact(null, null, PHONE);

        assertThat(service.trustedDeclaredPhone(u, contact)).isEmpty();
        assertThat(service.trustedDeclaredPhone(u, contact)).isEmpty();
        verify(firebaseContact, times(1)).findPhoneOwner(PHONE);
    }

    @Test
    void findByPhone_neverCached_eachCallReadsFirebase() {
        UserEntity u = yadonyVerified("u");
        ownerNotFound();
        candidates(u);
        claims(Map.of("u", PHONE));

        service.findByPhone(PHONE);
        service.findByPhone(PHONE);

        verify(firebaseContact, times(2)).findPhoneOwner(PHONE);
    }

    private void claims(Map<String, String> declaredByUid) {
        when(firebaseContact.getContacts(any())).thenAnswer(inv -> {
            Collection<String> uids = inv.getArgument(0);
            Map<String, FirebaseContactService.Contact> out = new java.util.HashMap<>();
            // Nul seulement quand un test restubbe la méthode (any() vaut null pendant when(...)).
            if (uids != null) {
                uids.forEach(uid -> out.put(uid, new FirebaseContactService.Contact(null, null, declaredByUid.get(uid))));
            }
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
    void findByPhone_yadonyVerifiedAccount_foundWithYadonyTrust() {
        UserEntity u = yadonyVerified("uid-y");
        ownerNotFound();
        candidates(u);
        claims(Map.of("uid-y", PHONE));

        assertThat(service.findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(u, PhoneMatch.Trust.YADONY));
    }

    @Test
    void findByPhone_onlyDeclaredCandidates_noneAndNoFirebaseRead() {
        UserEntity a = declaredUser("uid-a");
        UserEntity b = declaredUser("uid-b");
        ownerNotFound();
        candidates(a, b);

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
        verify(firebaseContact, never()).getContacts(any());
    }

    @Test
    void findByPhone_yadonyBeatsDeclaredSquatter_foundYadony() {
        UserEntity y = yadonyVerified("uid-y");
        UserEntity d = declaredUser("uid-d");
        ownerNotFound();
        candidates(y, d);
        claims(Map.of("uid-y", PHONE, "uid-d", PHONE));

        assertThat(service.findByPhone(PHONE)).isEqualTo(new PhoneMatch.Found(y, PhoneMatch.Trust.YADONY));
    }

    @Test
    void findByPhone_twoYadonyAccounts_ambiguous() {
        UserEntity y1 = yadonyVerified("uid-y1");
        UserEntity y2 = yadonyVerified("uid-y2");
        ownerNotFound();
        candidates(y1, y2);
        claims(Map.of("uid-y1", PHONE, "uid-y2", PHONE));

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.Ambiguous.class);
    }

    @Test
    void findByPhone_yadonyRowWithStaleClaim_noneAndSingleBatchedRead() {
        UserEntity y = yadonyVerified("uid-y");
        UserEntity d = declaredUser("uid-d");
        ownerNotFound();
        candidates(y, d);
        claims(Map.of("uid-y", "+224629999999", "uid-d", PHONE));

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
        verify(firebaseContact, times(1)).getContacts(any());
        verify(firebaseContact, never()).getContact(anyString());
    }

    @Test
    void findByPhone_yadonyRowWithSmsNumber_dropped() {
        UserEntity y = yadonyVerified("uid-y");
        ownerNotFound();
        candidates(y);
        when(firebaseContact.getContacts(any())).thenReturn(
                Map.of("uid-y", new FirebaseContactService.Contact("+33612345678", null, PHONE)));

        assertThat(service.findByPhone(PHONE)).isInstanceOf(PhoneMatch.None.class);
    }

    @Test
    void findAllByPhone_staleAndSmsNumberCandidates_dropped_validKept() {
        UserEntity stale = declaredUser("uid-stale");
        UserEntity sms = declaredUser("uid-sms2");
        UserEntity ok = declaredUser("uid-ok");
        ownerNotFound();
        candidates(stale, sms, ok);
        when(firebaseContact.getContacts(any())).thenReturn(Map.of(
                "uid-stale", new FirebaseContactService.Contact(null, null, "+224629999999"),
                "uid-sms2", new FirebaseContactService.Contact("+33612345678", null, PHONE),
                "uid-ok", new FirebaseContactService.Contact(null, null, PHONE)));

        assertThat(service.findAllByPhone(PHONE)).containsExactly(ok);
        verify(firebaseContact, times(1)).getContacts(any());
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
        verify(userRepository, never()).findTop3ByDeclaredPhoneHashAndDeclaredPhoneLevel(any(), any());
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
