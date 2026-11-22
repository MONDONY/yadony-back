package com.yadony.api.admin;

import com.yadony.api.auth.DeclaredPhoneHasher;
import com.yadony.api.auth.FirebaseContactService;
import com.yadony.api.auth.UserEntity;
import com.yadony.api.auth.UserRepository;
import com.yadony.api.common.AuditService;
import com.yadony.api.common.YadonyBusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AdminDeclaredPhoneService")
class AdminDeclaredPhoneServiceTest {

    private final UserRepository users = mock(UserRepository.class);
    private final FirebaseContactService firebase = mock(FirebaseContactService.class);
    private final AuditService audit = mock(AuditService.class);

    @Test
    void verify_unknownUser_404() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());
        var service = new AdminDeclaredPhoneService(users, firebase, new DeclaredPhoneHasher("k"), audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), "+2250707070707"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode()).isEqualTo("user-not-found"));
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void verify_hasherNotConfigured_503Unavailable_noWrite() {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("u");
        user.setDeclaredPhoneHash("h");
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.isAvailable()).thenReturn(true);
        when(firebase.readContactStrict("u")).thenReturn(Optional.of(new FirebaseContactService.Contact(null, null, "+2250707070707")));
        var service = new AdminDeclaredPhoneService(users, firebase, new DeclaredPhoneHasher(""), audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), "+2250707070707"))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> {
                    YadonyBusinessException ex = (YadonyBusinessException) e;
                    assertThat(ex.getErrorCode()).isEqualTo("declared-phone-unavailable");
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void verify_firebaseNotAvailable_503Unavailable_noWrite() {
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("k");
        UserEntity user = declaredUser(hasher, PHONE);
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.isAvailable()).thenReturn(false);
        var service = new AdminDeclaredPhoneService(users, firebase, hasher, audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), PHONE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("declared-phone-unavailable"));
        verify(users, never()).saveAndFlush(any());
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void verify_ownerLookupNull_treatedAsOutage_503() {
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("k");
        UserEntity user = declaredUser(hasher, PHONE);
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.isAvailable()).thenReturn(true);
        when(firebase.readContactStrict("u")).thenReturn(Optional.of(new FirebaseContactService.Contact(null, null, PHONE)));
        when(firebase.findPhoneOwner(PHONE)).thenReturn(null);
        var service = new AdminDeclaredPhoneService(users, firebase, hasher, audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), PHONE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("declared-phone-unavailable"));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void verify_ownerIsThisAccountBySms_phoneAlreadySet_noWrite() {
        // La lecture fraîche n'a pas vu de numéro SMS, mais Firebase désigne ce compte comme propriétaire
        // (rattachement SMS entre les deux lectures) : rien à valider, la déclaration va tomber.
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("k");
        UserEntity user = declaredUser(hasher, PHONE);
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.isAvailable()).thenReturn(true);
        when(firebase.readContactStrict("u")).thenReturn(Optional.of(new FirebaseContactService.Contact(null, null, PHONE)));
        when(firebase.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.found("u"));
        var service = new AdminDeclaredPhoneService(users, firebase, hasher, audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), PHONE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("phone-already-set"));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void verify_yadonyConflictReadInDatabase_withoutBatchClaimRead() {
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("k");
        UserEntity user = declaredUser(hasher, PHONE);
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.isAvailable()).thenReturn(true);
        when(firebase.readContactStrict("u")).thenReturn(Optional.of(new FirebaseContactService.Contact(null, null, PHONE)));
        when(firebase.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
        when(users.existsByDeclaredPhoneHashAndDeclaredPhoneLevelAndIdNot(
                hasher.hash(PHONE), com.yadony.api.auth.DeclaredPhoneLevel.YADONY_VERIFIED, user.getId()))
                .thenReturn(true);
        var service = new AdminDeclaredPhoneService(users, firebase, hasher, audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), PHONE))
                .isInstanceOf(YadonyBusinessException.class)
                .satisfies(e -> assertThat(((YadonyBusinessException) e).getErrorCode())
                        .isEqualTo("declared-phone-conflict"));
        verify(firebase, never()).getContacts(any());
        verify(users, never()).saveAndFlush(any());
    }

    // ── Constat 7 : lecture stricte, une panne Firebase n'est jamais « aucun numéro déclaré » ──

    @Test
    void verify_strictContactReadFails_503Unavailable_not409Missing() {
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("k");
        UserEntity user = declaredUser(hasher, PHONE);
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.isAvailable()).thenReturn(true);
        when(firebase.readContactStrict("u")).thenReturn(Optional.empty());
        var service = new AdminDeclaredPhoneService(users, firebase, hasher, audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("declared-phone-unavailable");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                });
        verify(firebase, never()).getFreshContact(any());
        verify(users, never()).saveAndFlush(any());
    }

    // ── Constat 3 : compte Firebase fantôme (numéro SMS sans compte Yadony actif) ──

    @Test
    void verify_smsOwnerWithoutLiveLocalAccount_409HeldByUnregistered_noWrite() {
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("k");
        UserEntity user = declaredUser(hasher, PHONE);
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.isAvailable()).thenReturn(true);
        when(firebase.readContactStrict("u")).thenReturn(Optional.of(new FirebaseContactService.Contact(null, null, PHONE)));
        when(firebase.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.found("uid-phantom"));
        when(users.findByFirebaseUid("uid-phantom")).thenReturn(Optional.empty());
        var service = new AdminDeclaredPhoneService(users, firebase, hasher, audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("declared-phone-held-by-unregistered-account");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        // Échec fermé : rien n'est validé, rien n'est libéré côté Firebase.
        verify(users, never()).saveAndFlush(any());
        verify(firebase, never()).clearPhone(any());
        verify(firebase, never()).deleteAccount(any());
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void verify_smsOwnerWithLiveLocalAccount_409Conflict() {
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("k");
        UserEntity user = declaredUser(hasher, PHONE);
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.isAvailable()).thenReturn(true);
        when(firebase.readContactStrict("u")).thenReturn(Optional.of(new FirebaseContactService.Contact(null, null, PHONE)));
        when(firebase.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.found("uid-sms"));
        UserEntity smsOwner = new UserEntity();
        smsOwner.setFirebaseUid("uid-sms");
        when(users.findByFirebaseUid("uid-sms")).thenReturn(Optional.of(smsOwner));
        var service = new AdminDeclaredPhoneService(users, firebase, hasher, audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("declared-phone-conflict"));
        verify(users, never()).saveAndFlush(any());
    }

    // ── Constat 2 : deux validations simultanées, l'index unique partiel tranche ──

    @Test
    void verify_uniqueIndexViolation_409Conflict_noAudit() {
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("k");
        UserEntity user = declaredUser(hasher, PHONE);
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.isAvailable()).thenReturn(true);
        when(firebase.readContactStrict("u")).thenReturn(Optional.of(new FirebaseContactService.Contact(null, null, PHONE)));
        when(firebase.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
        when(users.saveAndFlush(user)).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement", new RuntimeException(
                        "ERROR: duplicate key value violates unique constraint \"uq_users_declared_phone_yadony\"")));
        var service = new AdminDeclaredPhoneService(users, firebase, hasher, audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), PHONE))
                .isInstanceOfSatisfying(YadonyBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo("declared-phone-conflict");
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                });
        verify(audit, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void verify_otherIntegrityViolation_isNotDisguisedAsConflict() {
        DeclaredPhoneHasher hasher = new DeclaredPhoneHasher("k");
        UserEntity user = declaredUser(hasher, PHONE);
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.of(user));
        when(firebase.isAvailable()).thenReturn(true);
        when(firebase.readContactStrict("u")).thenReturn(Optional.of(new FirebaseContactService.Contact(null, null, PHONE)));
        when(firebase.findPhoneOwner(PHONE)).thenReturn(FirebaseContactService.PhoneOwnerLookup.notFound());
        var other = new org.springframework.dao.DataIntegrityViolationException("value too long for column");
        when(users.saveAndFlush(user)).thenThrow(other);
        var service = new AdminDeclaredPhoneService(users, firebase, hasher, audit);

        assertThatThrownBy(() -> service.verify(id, UUID.randomUUID(), PHONE)).isSameAs(other);
    }

    private static final String PHONE = "+2250707070707";

    private static UserEntity declaredUser(DeclaredPhoneHasher hasher, String phone) {
        UserEntity user = new UserEntity();
        user.setFirebaseUid("u");
        user.setDeclaredPhoneHash(hasher.hash(phone));
        user.setDeclaredPhoneLevel(com.yadony.api.auth.DeclaredPhoneLevel.DECLARED);
        return user;
    }
}
